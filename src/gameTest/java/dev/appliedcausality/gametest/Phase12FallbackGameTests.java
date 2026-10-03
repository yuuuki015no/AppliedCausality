package dev.appliedcausality.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.CraftingServiceStorage;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.content.AppliedCausalityContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static dev.appliedcausality.gametest.Phase11StandardAe2GameTests.setActiveNode;

/** Stock normal dispatch behind an ICraftingProvider-only compatibility boundary.
 * Real provider/recipes/NBT/ticker/removal; controlled grid and storage acceptance. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase12FallbackGameTests {
    private Phase12FallbackGameTests() {}

    static Phase21PerformanceProbe.PreparedInvocation preparePhase21FallbackInvocation(
            GameTestHelper helper) throws Exception {
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        var provider = fixture.addSmeltingProvider(helper.absolutePos(new BlockPos(1, 1, 1)));
        fixture.service.removeNode(fixture.providerNodes.get(0));
        fixture.register(normalOnly(provider));
        fixture.storage.items.set(fixture.raw, 1);
        var plan = fixture.calculate(fixture.ingot, 1);
        fixture.submit(plan, fixture.requester);
        var job = fixture.job();
        var task = job.bulk$tasks().iterator().next();
        int[] consumed = { -1 };
        return new Phase21PerformanceProbe.PreparedInvocation(
                "applied_causality_normal_fallback",
                1,
                plan.bytes(),
                () -> consumed[0] = fixture.execute(),
                () -> require(
                        consumed[0] == 1
                                && task.remaining() == 0
                                && job.bulk$getWaitingFor(fixture.ingot) == 1
                                && provider.isBusy(),
                        "Phase 21 normal fallback benchmark dispatch failed"));
    }

    @GameTest(template = "empty")
    public static void normalCpuRegistersWaitingBeforeLaterTickOutput(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var provider = f.addSmeltingProvider(helper.absolutePos(new BlockPos(1, 1, 1)));
        // Hide BCP at the compatibility boundary; do not change production Mixins.
        f.service.removeNode(f.providerNodes.get(0));
        f.register(normalOnly(provider));
        var clusters = CraftingService.class.getDeclaredField("craftingCPUClusters");
        clusters.setAccessible(true);
        @SuppressWarnings("unchecked")
        var registered = (Set<CraftingCPUCluster>) clusters.get(f.service);
        registered.add(f.cluster);
        var mounted = new ArrayList<MEStorage>();
        new CraftingServiceStorage(f.service).mountInventories((storage, priority) -> mounted.add(storage));
        var cpuStorage = mounted.get(0);
        f.networkStorage = new MEStorage() {
            @Override
            public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                return f.storage.extract(key, amount, mode, source);
            }

            @Override
            public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
                require(f.logic.getWaitingFor(key) >= amount, "output arrived before normal waiting registration");
                return cpuStorage.insert(key, amount, mode, source);
            }

            @Override
            public Component getDescription() { return Component.literal("Phase 12 normal CPU routing"); }
        };
        f.storage.items.set(f.raw, 2);
        f.submit(f.calculate(f.ingot, 2), f.requester);
        require(f.execute() == 1, "normal fallback did not accept one operation");
        require(f.job().bulk$tasks().iterator().next().remaining() == 1, "fallback executed more than one operation");
        require(f.job().bulk$getWaitingFor(f.ingot) == 1 && f.requester.accepted == 0, "waiting race");
        require(provider.isBusy() && f.execute() == 0, "pending queue did not apply backpressure");
        tick(provider);
        require(f.requester.accepted == 0, "same-tick output escaped");
        helper.runAfterDelay(1, () -> {
            tick(provider);
            require(f.requester.accepted == 1 && !provider.isBusy(), "first operation not delivered");
            require(f.execute() == 1 && f.requester.accepted == 1, "second operation failed or was synchronous");
        });
        helper.runAfterDelay(2, () -> {
            tick(provider);
            require(f.requester.accepted == 2 && f.cpu.bulk$getJob() == null, "normal job did not finish");
            require(!provider.hasFallbackRecovery(), "delivered output duplicated in provider");
            require(f.energyCalls > 0, "stock dispatch path was not exercised");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void invalidReplacedAndReloadInvalidatedPatternsCannotFallback(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var provider = f.addSmeltingProvider(helper.absolutePos(new BlockPos(1, 1, 1)));
        var valid = provider.getAvailablePatterns().get(0);
        var wrong = f.pattern(f.raw, appeng.api.stacks.AEItemKey.of(Items.DIAMOND));
        var input = inputs(f.raw, 1);
        require(!provider.pushPattern(wrong, input), "fabricated output was accepted");
        require(!provider.pushPattern(valid, inputs(f.raw, 0)), "missing input was accepted");
        require(!provider.pushPattern(valid, inputs(f.raw, 2)), "excess input was accepted");
        provider.invalidateForRecipeReload();
        require(!provider.pushPattern(valid, input), "stale cached pattern was accepted during reload");
        provider.rebuildAfterRecipeReload();
        provider.getInternalInventory().setItemDirect(0, PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[] {new GenericStack(f.raw, 1)},
                new GenericStack[] {new GenericStack(appeng.api.stacks.AEItemKey.of(Items.DIAMOND), 1)}));
        require(provider.getAvailablePatterns().isEmpty() && !provider.pushPattern(valid, input),
                "replaced definition retained old authorization");
        require(!provider.hasFallbackRecovery() && input[0].get(f.raw) == 1, "rejection changed ownership");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void saveReloadAndDisconnectedRetryPreserveCommittedOutput(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var original = f.addSmeltingProvider(helper.absolutePos(new BlockPos(1, 1, 1)));
        require(original.pushPattern(original.getAvailablePatterns().get(0), inputs(f.raw, 1)), "push failed");
        var saved = new CompoundTag();
        original.saveAdditional(saved);
        original.onChunkUnloaded();
        var restored = restore(helper, f, saved);
        restored.invalidateForRecipeReload();
        require(restored.getAvailablePatterns().isEmpty(), "authoritative model was persisted");
        f.storage.acceptInsert = false;
        tick(restored); // Load barrier.
        helper.runAfterDelay(1, () -> {
            tick(restored);
            require(pending(restored) == 1, "zero acceptance lost output");
            try {
                setActiveNode(restored, restored.getMainNode(), helper, f.grid, false);
            } catch (Exception exception) { throw testFailure(exception); }
        });
        helper.runAfterDelay(2, () -> {
            f.storage.acceptInsert = true;
            tick(restored);
            require(pending(restored) == 1 && f.storage.items.get(f.ingot) == 0, "disconnected delivery ran");
            try {
                setActiveNode(restored, restored.getMainNode(), helper, f.grid, true);
            } catch (Exception exception) { throw testFailure(exception); }
            tick(restored);
            require(pending(restored) == 0 && f.storage.items.get(f.ingot) == 1, "restored output not delivered exactly");
            tick(restored);
            require(f.storage.items.get(f.ingot) == 1, "duplicate retry");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void removalDuringInsertionRecoversOnlyExactRemainder(GameTestHelper helper) throws Exception {
        // Seed a committed checkpoint to exercise partial acceptance with more
        // than the built-in smelting recipe's single output unit.
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var original = f.addSmeltingProvider(helper.absolutePos(new BlockPos(1, 1, 1)));
        require(original.pushPattern(original.getAvailablePatterns().get(0), inputs(f.raw, 1)), "push failed");
        var saved = new CompoundTag();
        original.saveAdditional(saved);
        saved.getCompound("applied_causality").getCompound("fallback")
                .getList("outputs", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", 10);
        var position = new BlockPos(2, 2, 2);
        helper.setBlock(position, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getBlockEntity(position);
        provider.loadTag(saved);
        setActiveNode(provider, provider.getMainNode(), helper, f.grid, true);
        f.networkStorage = new MEStorage() {
            @Override
            public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
                require(amount == 10, "wrong insertion amount");
                tick(provider); // Recursive delivery must do nothing.
                helper.getLevel().destroyBlock(provider.getBlockPos(), true);
                require(!provider.pushPattern(original.getAvailablePatterns().get(0), inputs(f.raw, 1)),
                        "removed provider accepted input");
                return 2;
            }

            @Override
            public Component getDescription() { return Component.literal("Remove provider during insertion"); }
        };
        tick(provider);
        helper.runAfterDelay(2, () -> {
            var items = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(helper.absolutePos(position)).inflate(1.5));
            var carriers = items.stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get())).toList();
            require(carriers.size() == 1 && carriers.get(0).getCount() == 1, "recovery duplicated or lost provider block");
            require(pending(carriers.get(0).getTag().getCompound("BlockEntityTag")) == 8,
                    "recovery did not contain exact partial remainder");
            require(pending(provider) == 0, "removed BE retained duplicate ownership");
            try {
                var restored = restore(helper, f, carriers.get(0).getTag().getCompound("BlockEntityTag"));
                f.networkStorage = f.storage;
                tick(restored);
                helper.runAfterDelay(1, () -> {
                    tick(restored);
                    require(f.storage.items.get(f.ingot) == 8 && pending(restored) == 0,
                            "recovery carrier could not resume exact output delivery");
                    helper.succeed();
                });
            } catch (Exception exception) { throw testFailure(exception); }
        });
    }

    @GameTest(template = "empty")
    public static void recoveryDropAndClearAreIdempotent(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var provider = f.addSmeltingProvider(helper.absolutePos(new BlockPos(1, 1, 1)));
        require(provider.pushPattern(provider.getAvailablePatterns().get(0), inputs(f.raw, 1)), "push failed");
        var drops = new ArrayList<ItemStack>();
        provider.addAdditionalDrops(helper.getLevel(), provider.getBlockPos(), drops);
        provider.clearContent();
        provider.addAdditionalDrops(helper.getLevel(), provider.getBlockPos(), drops);
        var carriers = drops.stream().filter(stack -> stack.is(AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get()))
                .toList();
        require(carriers.size() == 1, "wrench drop/clear repeated recovery output");
        require(pending(carriers.get(0).getTag().getCompound("BlockEntityTag")) == 1 && pending(provider) == 0,
                "recovery ownership did not transfer");
        var normalLoot = Block.getDrops(provider.getBlockState(), helper.getLevel(), provider.getBlockPos(), provider);
        require(normalLoot.stream().noneMatch(stack -> stack.is(AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get())),
                "loot evaluated after removal minted a second provider block");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void carrierPlacementPreservesFluidAndLongQuantities(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var provider = f.addSmeltingProvider(helper.absolutePos(new BlockPos(1, 1, 1)));
        require(provider.pushPattern(provider.getAvailablePatterns().get(0), inputs(f.raw, 1)), "push failed");
        var saved = new CompoundTag();
        provider.saveAdditional(saved);
        var outputs = saved.getCompound("applied_causality").getCompound("fallback").getList("outputs", Tag.TAG_COMPOUND);
        outputs.getCompound(0).putLong("amount", Long.MAX_VALUE);
        var water = appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER);
        var fluid = water.toTagGeneric();
        fluid.putLong("amount", Long.MAX_VALUE);
        outputs.add(fluid);
        provider.loadTag(saved);
        provider.clearContent();
        require(provider.hasFallbackRecovery(), "bare inventory clear discarded committed output");
        var drops = new ArrayList<ItemStack>();
        provider.addAdditionalDrops(helper.getLevel(), provider.getBlockPos(), drops);
        var carrier = drops.stream().filter(stack -> stack.is(AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get()))
                .findFirst().orElseThrow();
        var position = helper.absolutePos(new BlockPos(1, 2, 1));
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        var context = new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, carrier,
                new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
        require(((BlockItem) carrier.getItem()).place(context).consumesAction(), "recovery carrier placement failed");
        var placed = (SingularPatternProviderBlockEntity) helper.getLevel().getBlockEntity(position);
        require(placed != null, "carrier did not place a provider");
        var roundTrip = new CompoundTag();
        placed.saveAdditional(roundTrip);
        var restoredOutputs = roundTrip.getCompound("applied_causality").getCompound("fallback")
                .getList("outputs", Tag.TAG_COMPOUND);
        require(restoredOutputs.size() == 2, "carrier expanded or lost distinct keys");
        var expected = Set.of(f.ingot, water);
        for (var tag : restoredOutputs) {
            var entry = (CompoundTag) tag;
            require(expected.contains(AEKey.fromTagGeneric(entry)) && entry.getLong("amount") == Long.MAX_VALUE,
                    "carrier altered exact long quantity");
        }
        require(carrier.isEmpty(), "placing the recovery item did not consume its old owner");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void canceledForgePlacementRetainsOnlyTheOriginalCarrier(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var provider = f.addSmeltingProvider(helper.absolutePos(new BlockPos(1, 1, 1)));
        require(provider.pushPattern(provider.getAvailablePatterns().get(0), inputs(f.raw, 1)), "push failed");
        var drops = new ArrayList<ItemStack>();
        provider.addAdditionalDrops(helper.getLevel(), provider.getBlockPos(), drops);
        var carrier = drops.stream().filter(stack -> stack.is(AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get()))
                .findFirst().orElseThrow();
        var originalTag = carrier.getTag().copy();
        var position = helper.absolutePos(new BlockPos(1, 2, 1));
        var bounds = new AABB(position).inflate(0.5);
        var player = FakePlayerFactory.getMinecraft(helper.getLevel());
        var cancellation = new CancelPlacement(position);
        MinecraftForge.EVENT_BUS.register(cancellation);
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                var context = new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, carrier,
                        new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
                require(carrier.useOn(context) == net.minecraft.world.InteractionResult.FAIL,
                        "Forge placement event did not cancel item use");
                require(cancellation.calls == attempt + 1, "test bypassed the Forge placement event");
                require(helper.getLevel().getBlockState(position).isAir(), "snapshot rollback did not restore air");
                require(carrier.getCount() == 1 && originalTag.equals(carrier.getTag()), "canceled placement lost original owner");
                require(helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds).isEmpty(),
                        "placement rollback duplicated recovery output");
            }
        } finally {
            MinecraftForge.EVENT_BUS.unregister(cancellation);
        }
        var accepted = new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, carrier,
                new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
        require(carrier.useOn(accepted).consumesAction() && carrier.isEmpty(), "uncanceled placement failed");
        var placed = (SingularPatternProviderBlockEntity) helper.getLevel().getBlockEntity(position);
        require(placed != null && pending(placed) == 1, "successful placement lost fallback ownership");
        helper.getLevel().destroyBlock(position, true);
        var recovered = helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds).stream()
                .map(ItemEntity::getItem)
                .filter(stack -> stack.is(AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get())).toList();
        require(recovered.size() == 1 && recovered.get(0).getCount() == 1
                        && pending(recovered.get(0).getTag().getCompound("BlockEntityTag")) == 1,
                "normal removal after successful placement must recover exactly once");
        helper.succeed();
    }

    private static final class CancelPlacement {
        private final BlockPos position;
        private int calls;

        private CancelPlacement(BlockPos position) {
            this.position = position;
        }

        @SubscribeEvent
        public void onPlace(BlockEvent.EntityPlaceEvent event) {
            if (event.getPos().equals(position)) {
                calls++;
                event.setCanceled(true);
            }
        }
    }

    @GameTest(template = "empty")
    public static void callbackRemovalReentryAndUnknownAcceptanceKeepAnOwner(GameTestHelper helper) throws Exception {
        var fixtures = new ArrayList<Phase11StandardAe2GameTests.Fixture>();
        var providers = new ArrayList<SingularPatternProviderBlockEntity>();
        for (boolean unknown : new boolean[] {false, true}) {
            var f = new Phase11StandardAe2GameTests.Fixture(helper);
            var provider = f.addSmeltingProvider(helper.absolutePos(new BlockPos(1, 1, 1)));
            var pattern = provider.getAvailablePatterns().get(0);
            require(provider.pushPattern(pattern, inputs(f.raw, 1)), "push failed");
            f.networkStorage = new MEStorage() {
                @Override
                public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
                    var deferred = new ArrayList<ItemStack>();
                    provider.addAdditionalDrops(helper.getLevel(), provider.getBlockPos(), deferred);
                    tick(provider); // Reentry after removal request, before BE removal.
                    provider.clearContent();
                    provider.setRemoved();
                    if (unknown) throw new IllegalStateException("unknown callback outcome");
                    return 0;
                }

                @Override
                public Component getDescription() { return Component.literal("Removal reentry"); }
            };
            fixtures.add(f);
            providers.add(provider);
        }
        helper.runAfterDelay(1, () -> {
            tick(providers.get(0));
            try {
                tick(providers.get(1));
                throw new net.minecraft.gametest.framework.GameTestAssertException("callback exception swallowed");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().equals("unknown callback outcome"), "original callback failure masked");
            }
            var carriers = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(new BlockPos(1, 1, 1))).inflate(0.5))
                    .stream().map(ItemEntity::getItem)
                    .filter(stack -> stack.is(AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get())).toList();
            require(carriers.size() == 2, "deferred removal lost or duplicated a carrier");
            require(carriers.stream().filter(stack -> stack.getTag().getCompound("BlockEntityTag")
                    .getCompound("applied_causality").getCompound("fallback").getBoolean("uncertain")).count() == 1,
                    "unknown acceptance was not quarantined in a durable owner");
            for (var carrier : carriers) {
                require(pending(carrier.getTag().getCompound("BlockEntityTag")) == 1, "carrier lost pending record");
            }
            helper.succeed();
        });
    }

    private static SingularPatternProviderBlockEntity restore(GameTestHelper helper,
            Phase11StandardAe2GameTests.Fixture f, CompoundTag saved) throws Exception {
        var provider = new SingularPatternProviderBlockEntity(helper.absolutePos(new BlockPos(1, 1, 1)),
                AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get().defaultBlockState());
        provider.setLevel(helper.getLevel());
        provider.loadTag(saved);
        setActiveNode(provider, provider.getMainNode(), helper, f.grid, true);
        return provider;
    }

    @GameTest(template = "empty")
    public static void phase17UnloadDuringPartialDeliveryRestoresOnlySettledRemainder(GameTestHelper helper)
            throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        var source = f.addSmeltingProvider(helper.absolutePos(new BlockPos(1, 1, 1)));
        require(source.pushPattern(source.getAvailablePatterns().get(0), inputs(f.raw, 1)), "push failed");
        var seed = new CompoundTag();
        source.saveAdditional(seed);
        seed.getCompound("applied_causality").getCompound("fallback")
                .getList("outputs", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", 10);
        source.onChunkUnloaded();
        var original = restore(helper, f, seed);
        var callbackSave = new CompoundTag();
        f.networkStorage = new MEStorage() {
            @Override
            public long insert(AEKey key, long amount, Actionable mode, IActionSource action) {
                require(amount == 10, "unexpected unload offer");
                f.storage.items.add(key, 2);
                original.onChunkUnloaded();
                original.saveAdditional(callbackSave);
                return 2;
            }

            @Override
            public Component getDescription() { return Component.literal("Phase17 unload callback"); }
        };
        tick(original); // First restored tick cannot deliver before CPU waiting registration.
        helper.runAfterDelay(1, () -> {
            tick(original);
            require(pending(original) == 8 && f.storage.items.get(f.ingot) == 2,
                    "unload did not settle exact acceptance");
            var stableSave = new CompoundTag();
            original.saveAdditional(stableSave);
            try {
                var quarantined = restore(helper, f, callbackSave);
                var restored = restore(helper, f, stableSave);
                f.networkStorage = f.storage;
                tick(restored);
                helper.runAfterDelay(1, () -> {
                    tick(original);
                    tick(quarantined);
                    tick(restored);
                    tick(restored);
                    require(pending(original) == 8 && pending(quarantined) == 10
                                    && pending(restored) == 0 && f.storage.items.get(f.ingot) == 10,
                            "old owner or callback snapshot replayed output after unload");
                    helper.succeed();
                });
            } catch (Exception exception) { throw testFailure(exception); }
        });
    }

    private static ICraftingProvider normalOnly(SingularPatternProviderBlockEntity provider) {
        return new ICraftingProvider() {
            @Override
            public List<IPatternDetails> getAvailablePatterns() { return provider.getAvailablePatterns(); }
            @Override
            public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { return provider.pushPattern(pattern, inputs); }
            @Override
            public boolean isBusy() { return provider.isBusy(); }
        };
    }

    private static KeyCounter[] inputs(AEKey key, long amount) {
        var input = new KeyCounter();
        input.set(key, amount);
        return new KeyCounter[] {input};
    }

    private static void tick(SingularPatternProviderBlockEntity provider) {
        SingularPatternProviderBlockEntity.serverTick(provider.getLevel(), provider.getBlockPos(),
                provider.getBlockState(), provider);
    }

    private static long pending(SingularPatternProviderBlockEntity provider) {
        var saved = new CompoundTag();
        provider.saveAdditional(saved);
        return pending(saved);
    }

    private static long pending(CompoundTag saved) {
        return saved.getCompound("applied_causality").getCompound("fallback")
                .getList("outputs", Tag.TAG_COMPOUND).stream()
                .mapToLong(tag -> ((CompoundTag) tag).getLong("amount")).sum();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }

    private static RuntimeException testFailure(Exception cause) {
        var failure = new net.minecraft.gametest.framework.GameTestAssertException(cause.toString());
        failure.initCause(cause);
        return failure;
    }
}
