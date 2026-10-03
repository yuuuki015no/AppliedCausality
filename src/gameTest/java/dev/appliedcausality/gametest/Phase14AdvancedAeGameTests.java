package dev.appliedcausality.gametest;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.me.service.CraftingService;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.internal.budget.BulkDispatchBudgetService;
import dev.appliedcausality.internal.budget.IBulkDispatchBudget;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.internal.cpu.IBulkCpuBridge;
import dev.appliedcausality.internal.cpu.IBulkJobBridge;
import dev.appliedcausality.internal.provider.IBulkCraftingProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCluster;
import net.pedroksl.advanced_ae.common.entities.AdvCraftingBlockEntity;
import net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic;

/** Real AdvancedAE 1.3.5 job/CPU integration with controlled network boundaries. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase14AdvancedAeGameTests {
    // 32 source-confirmed 256 MiB storage units fit inside the default 7^3
    // quantum-computer envelope without relying on a data-entangler multiplier.
    private static final long SOURCE_VALID_CAPACITY = 32L * 256 * 1024 * 1024;
    private static final long LARGE_MULTIPLIER = (long) Integer.MAX_VALUE + 1;

    private Phase14AdvancedAeGameTests() {
    }

    static Phase21PerformanceProbe.PreparedInvocation preparePhase21AdvancedInvocation(
            GameTestHelper helper) throws Exception {
        var fixture = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        var provider = fixture.addSmeltingProvider(BlockPos.ZERO, fixture.raw, fixture.ingot);
        fixture.storage.items.set(fixture.raw, LARGE_MULTIPLIER);
        long bytes = Math.addExact(Math.multiplyExact(3, LARGE_MULTIPLIER), 16);
        var usedItems = new KeyCounter();
        usedItems.set(fixture.raw, LARGE_MULTIPLIER);
        fixture.submit(controlledPlan(
                fixture.ingot,
                LARGE_MULTIPLIER,
                bytes,
                usedItems,
                Map.of(provider.getAvailablePatterns().get(0), LARGE_MULTIPLIER)));
        var job = requireJob(fixture.cpu);
        var task = job.bulk$tasks().iterator().next();
        int[] consumed = { -1 };
        return new Phase21PerformanceProbe.PreparedInvocation(
                "applied_causality_advancedae_quantum",
                LARGE_MULTIPLIER,
                bytes,
                () -> consumed[0] = fixture.execute(),
                () -> require(
                        consumed[0] == 1
                                && task.remaining() == 0
                                && fixture.cpu.bulk$getJob() == null
                                && fixture.requester.accepted == LARGE_MULTIPLIER,
                        "Phase 21 AdvancedAE benchmark dispatch failed"));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void largeMultiplierUsesOneAdvancedSchedulerOperation(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        var provider = f.addSmeltingProvider(BlockPos.ZERO, f.raw, f.ingot);
        f.storage.items.set(f.raw, LARGE_MULTIPLIER);
        long bytes = Math.addExact(Math.multiplyExact(3, LARGE_MULTIPLIER), 16);
        var usedItems = new KeyCounter();
        usedItems.set(f.raw, LARGE_MULTIPLIER);
        var plan = controlledPlan(
                f.ingot,
                LARGE_MULTIPLIER,
                bytes,
                usedItems,
                Map.of(provider.getAvailablePatterns().get(0), LARGE_MULTIPLIER));
        require(plan.bytes() <= SOURCE_VALID_CAPACITY && plan.bytes() > Integer.MAX_VALUE,
                "source-valid AdvancedAE capacity did not admit the long-sized plan");
        f.submit(plan);

        var job = f.job();
        requireBulkReady(f, job, 1);
        var tasks = job.bulk$tasks().iterator();
        require(tasks.hasNext(), "AdvancedAE admission produced no task for long N");
        var task = tasks.next();
        require(task.remaining() == LARGE_MULTIPLIER, "AdvancedAE admission narrowed the task multiplier");
        require(f.execute() == 1, "AdvancedAE Bulk dispatch did not consume one scheduler operation");
        require(task.remaining() == 0, "AdvancedAE Bulk dispatch did not decrease the task by long N");
        require(((int[]) field(AdvCraftingCPULogic.class, "usedOps").get(f.logic))[0] == 0,
                "direct execute unexpectedly mutated AdvancedAE's tick-owned operation window");
        require(f.cpu.bulk$getJob() == null && f.requester.accepted == LARGE_MULTIPLIER,
                "AdvancedAE long-sized job did not complete exactly");
        require(f.storage.items.get(f.raw) == 0
                        && f.logic.getInventory().list.get(f.raw) == 0
                        && f.logic.getInventory().list.get(f.ingot) == 0
                        && job.bulk$outputEscrow().isEmpty(),
                "AdvancedAE long-sized ownership was duplicated or lost");
        require(f.requester.simulations == 1 && f.requester.deliveries == 1,
                "AdvancedAE delivery work scaled with N");
        require(provider.getInternalInventory().getStackInSlot(0).getCount() == 1,
                "AdvancedAE execution consumed the encoded pattern");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void multipleBulkPatternsShareHorizontalCapacityAndReturnToNormalPath(
            GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, SOURCE_VALID_CAPACITY, 1);
        var rawIron = AEItemKey.of(Items.RAW_IRON);
        var iron = AEItemKey.of(Items.IRON_INGOT);
        var rawGold = AEItemKey.of(Items.RAW_GOLD);
        var gold = AEItemKey.of(Items.GOLD_INGOT);
        var diamond = AEItemKey.of(Items.DIAMOND);
        var ironProvider = f.addSmeltingProvider(new BlockPos(1, 0, 0), rawIron, iron);
        var goldProvider = f.addSmeltingProvider(new BlockPos(2, 0, 0), rawGold, gold);
        var joinPattern = processingPattern(
                helper,
                new GenericStack[] { new GenericStack(iron, 1), new GenericStack(gold, 1) },
                new GenericStack[] { new GenericStack(diamond, 1) });
        var normalJoin = new NormalProvider(joinPattern);
        f.register(normalJoin);
        f.storage.items.set(rawIron, 1);
        f.storage.items.set(rawGold, 1);
        var usedItems = new KeyCounter();
        usedItems.set(rawIron, 1);
        usedItems.set(rawGold, 1);
        var tasks = new LinkedHashMap<IPatternDetails, Long>();
        tasks.put(ironProvider.getAvailablePatterns().get(0), 1L);
        tasks.put(goldProvider.getAvailablePatterns().get(0), 1L);
        tasks.put(joinPattern, 1L);
        f.submit(controlledPlan(diamond, 1, 25, usedItems, tasks));
        var job = f.job();
        requireBulkReady(f, job, 2);

        f.logic.tickCraftingLogic(f.energy, f.service);
        long ironTask = taskRemaining(job, ironProvider.getAvailablePatterns().get(0));
        long goldTask = taskRemaining(job, goldProvider.getAvailablePatterns().get(0));
        long joinTask = taskRemaining(job, joinPattern);
        long storedIron = f.logic.getInventory().list.get(iron);
        long storedGold = f.logic.getInventory().list.get(gold);
        require(ironTask == 0 && goldTask == 0,
                "AdvancedAE co-processor window task remainder: iron=" + ironTask + ", gold=" + goldTask);
        require(joinTask == 1 && storedIron == 1 && storedGold == 1,
                "AdvancedAE horizontal ownership: join=" + joinTask
                        + ", iron=" + storedIron + ", gold=" + storedGold);
        require(((int[]) field(AdvCraftingCPULogic.class, "usedOps").get(f.logic))[0] == 2,
                "AdvancedAE charged a Bulk multiplier instead of one operation per pattern");

        require(f.execute() == 1 && normalJoin.pushes == 1,
                "AdvancedAE did not leave the original normal provider path untouched");
        require(f.logic.insert(diamond, 1, Actionable.MODULATE) == 1
                        && f.requester.accepted == 1
                        && f.cpu.bulk$getJob() == null,
                "AdvancedAE mixed normal/Bulk job did not complete normally");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reentrantCancelWaitsForAdvancedCommitOwnership(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        var provider = f.addSmeltingProvider(BlockPos.ZERO, f.raw, f.ingot);
        f.storage.items.set(f.raw, 10);
        var usedItems = new KeyCounter();
        usedItems.set(f.raw, 10);
        f.submit(controlledPlan(
                f.ingot, 10, 46, usedItems, Map.of(provider.getAvailablePatterns().get(0), 10L)));
        var job = f.job();
        requireBulkReady(f, job, 1);
        var cancelCalls = new int[1];
        f.logic.addListener(key -> {
            if (key.equals(f.ingot)) {
                cancelCalls[0]++;
                f.logic.cancel();
            }
        });

        int consumed = f.execute();
        require(consumed == 1,
                "reentrant AdvancedAE cancellation dispatch=" + consumed
                        + ", rawInventory=" + f.logic.getInventory().list.get(f.raw)
                        + ", accepted=" + f.requester.accepted
                        + ", escrow=" + job.bulk$outputEscrow().get(f.ingot));
        require(cancelCalls[0] > 0 && f.cpu.bulk$getJob() == null,
                "reentrant AdvancedAE cancellation was not replayed after commit");
        require(f.storage.items.get(f.raw) == 0
                        && f.logic.getInventory().list.get(f.raw) == 0
                        && f.requester.accepted == 10
                        && f.logic.getInventory().list.get(f.ingot) == 0
                        && job.bulk$outputEscrow().isEmpty(),
                "reentrant AdvancedAE cancellation lost or duplicated committed ownership");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void finishingCpuRemainsGuardedDuringMultiCpuBlockEntityBreak(
            GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        var core = f.attachCore(new BlockPos(0, 3, 0), null);
        var provider = f.addSmeltingProvider(BlockPos.ZERO, f.raw, f.ingot);
        var pattern = provider.getAvailablePatterns().get(0);
        f.storage.items.set(f.raw, 17);

        var firstUsed = new KeyCounter();
        firstUsed.set(f.raw, 10);
        var first = f.submitStandalone(controlledPlan(
                f.ingot, 10, 46, firstUsed, Map.of(pattern, 10L)));
        var firstBridge = (IBulkCpuBridge) (Object) first.craftingLogic;
        requireBulkReady(f, requireJob(firstBridge), 1);

        var secondUsed = new KeyCounter();
        secondUsed.set(f.raw, 7);
        var second = f.submitStandalone(controlledPlan(
                f.ingot, 7, 37, secondUsed, Map.of(pattern, 7L)));
        requireBulkReady(f, requireJob((IBulkCpuBridge) (Object) second.craftingLogic), 1);
        require(f.cluster.getActiveCPUs().size() == 2 && f.storage.items.get(f.raw) == 0,
                "fixture did not retain two independently owning AdvancedAE CPUs");

        var breakCalls = new int[1];
        f.storage.onModulateInsert = () -> {
            breakCalls[0]++;
            core.breakCluster();
        };

        int consumed = first.craftingLogic.executeCrafting(1, f.service, f.energy, helper.getLevel());
        require(consumed == 1 && breakCalls[0] == 1 && f.cluster.isDestroyed(),
                "deferred AdvancedAE BlockEntity break did not replay exactly once");
        require(f.storage.items.get(f.ingot) == 10 && f.storage.items.get(f.raw) == 7,
                "AdvancedAE multi-CPU return did not preserve exact network ownership");
        require(first.craftingLogic.getInventory().list.isEmpty()
                        && second.craftingLogic.getInventory().list.isEmpty(),
                "AdvancedAE destroyed CPUs retained duplicate inventory ownership");
        require(f.dropped(Items.IRON_INGOT) == 0 && f.dropped(Items.RAW_IRON) == 0,
                "AdvancedAE BlockEntity break duplicated a network-accepted CPU inventory");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nonEmptyEscrowCancellationReturnDefersReentrantBlockEntityBreak(
            GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        var core = f.attachCore(new BlockPos(0, 3, 0), null);
        var provider = f.addSmeltingProvider(BlockPos.ZERO, f.raw, f.ingot);
        f.storage.items.set(f.raw, 10);
        f.requester.limit = 2;
        var usedItems = new KeyCounter();
        usedItems.set(f.raw, 10);
        f.submit(controlledPlan(
                f.ingot,
                10,
                46,
                usedItems,
                Map.of(provider.getAvailablePatterns().get(0), 10L)));
        var job = f.job();
        requireBulkReady(f, job, 1);
        require(f.execute() == 1
                        && job.bulk$outputEscrow().get(f.ingot) == 8
                        && f.requester.accepted == 2,
                "fixture did not retain a partial AdvancedAE output escrow");

        var nestedBreaks = new int[1];
        f.storage.onModulateInsert = () -> {
            nestedBreaks[0]++;
            core.breakCluster();
        };
        core.breakCluster();

        require(nestedBreaks[0] == 1 && f.cluster.isDestroyed() && f.cpu.bulk$getJob() == null,
                "AdvancedAE escrow cancellation did not replay the nested BlockEntity break exactly once");
        require(f.requester.accepted == 2
                        && f.storage.items.get(f.ingot) == 8
                        && f.logic.getInventory().list.get(f.ingot) == 0
                        && job.bulk$outputEscrow().isEmpty()
                        && f.dropped(Items.IRON_INGOT) == 0,
                "AdvancedAE escrow cancellation return was lost or paid twice during reentrant break");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void advancedClusterSaveLoadRetriesEscrowExactlyOnce(GameTestHelper helper) throws Exception {
        var original = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        var originalCore = original.attachCore(new BlockPos(0, 3, 0), null);
        var provider = original.addSmeltingProvider(BlockPos.ZERO, original.raw, original.ingot);
        original.storage.items.set(original.raw, 1_000);
        original.requester.limit = 2;
        var usedItems = new KeyCounter();
        usedItems.set(original.raw, 1_000);
        original.submit(controlledPlan(
                original.ingot,
                1_000,
                3_016,
                usedItems,
                Map.of(provider.getAvailablePatterns().get(0), 1_000L)));
        var originalJob = original.job();
        requireBulkReady(original, originalJob, 1);
        int consumed = original.execute();
        long escrow = originalJob.bulk$outputEscrow().get(original.ingot);
        long remaining = originalJob.bulk$getRemainingFinalAmount();
        require(consumed == 1 && escrow == 998 && remaining == 998,
                "AdvancedAE partial state: dispatch=" + consumed
                        + ", escrow=" + escrow
                        + ", remaining=" + remaining
                        + ", accepted=" + original.requester.accepted
                        + ", rawInventory=" + original.logic.getInventory().list.get(original.raw));

        var savedCore = new CompoundTag();
        originalCore.saveAdditional(savedCore);
        require(savedCore.getBoolean("core")
                        && savedCore.getList("cpuList", Tag.TAG_COMPOUND).size() == 1,
                "AdvancedAE BlockEntity save omitted its active CPU");
        var savedRequesterLink = new CompoundTag();
        original.requesterLink.writeToNBT(savedRequesterLink);
        var completedBefore = original.logic.getElapsedTimeTracker().writeToNBT()
                .getCompound("completedWork").copy();
        require(original.cluster.isActive(),
                "AdvancedAE original cluster was not active before chunk unload");
        originalCore.onChunkUnloaded();
        require(!original.cluster.isActive(),
                "AdvancedAE chunk unload did not deactivate the original production cluster");

        long oldAcceptedBeforeTick = original.requester.accepted;
        int oldDeliveriesBeforeTick = original.requester.deliveries;
        long oldEscrowBeforeTick = originalJob.bulk$outputEscrow().get(original.ingot);
        long oldRemainingBeforeTick = originalJob.bulk$getRemainingFinalAmount();
        long oldCpuInventoryBeforeTick = original.logic.getInventory().list.get(original.ingot);
        long oldNetworkBeforeTick = original.storage.items.get(original.ingot);
        long oldDropsBeforeTick = original.dropped(Items.IRON_INGOT);
        original.logic.tickCraftingLogic(original.energy, original.service);
        require(!original.cluster.isActive()
                        && original.cpu.bulk$getJob() == originalJob
                        && original.requester.accepted == oldAcceptedBeforeTick
                        && original.requester.deliveries == oldDeliveriesBeforeTick
                        && originalJob.bulk$outputEscrow().get(original.ingot) == oldEscrowBeforeTick
                        && originalJob.bulk$getRemainingFinalAmount() == oldRemainingBeforeTick
                        && original.logic.getInventory().list.get(original.ingot) == oldCpuInventoryBeforeTick
                        && original.storage.items.get(original.ingot) == oldNetworkBeforeTick
                        && original.dropped(Items.IRON_INGOT) == oldDropsBeforeTick,
                "AdvancedAE inactive old CPU changed output ownership after chunk unload");

        var restored = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        var restoredCore = restored.attachCore(new BlockPos(3, 3, 0), savedCore);
        require(restoredCore.isCoreBlock()
                        && restoredCore.getCluster() == restored.cluster
                        && restoredCore.getPreviousState() == null,
                "AdvancedAE BlockEntity did not consume saved state during cluster reformation");
        restored.bindLoadedCpu();
        var restoredJob = restored.job();
        require(restoredJob.bulk$outputEscrow().get(restored.ingot) == 998
                        && restoredJob.bulk$getRemainingFinalAmount() == 998
                        && restored.logic.getInventory().list.get(restored.ingot) == 0,
                "AdvancedAE cluster load lost or duplicated escrow ownership");
        var restoredTracker = restored.logic.getElapsedTimeTracker();
        var completedAfterLoad = restoredTracker.writeToNBT().getCompound("completedWork").copy();
        String itemType = restored.ingot.getType().getId().toString();
        require(completedBefore.getLong(itemType) == completedAfterLoad.getLong(itemType),
                "AdvancedAE cluster load changed item progress: before=" + completedBefore
                        + ", loaded=" + completedAfterLoad);

        var restoredRequesterLink = new CraftingLink(savedRequesterLink, restored.requester);
        restored.service.addLink((CraftingLink) restored.logic.getLastLink());
        restored.service.addLink(restoredRequesterLink);
        restored.requester.limit = Long.MAX_VALUE;
        restored.logic.tickCraftingLogic(restored.energy, restored.service);

        require(restored.cpu.bulk$getJob() == null
                        && original.requester.accepted + restored.requester.accepted == 1_000
                        && restoredJob.bulk$outputEscrow().isEmpty()
                        && restoredJob.bulk$getRemainingFinalAmount() == 0,
                "AdvancedAE restored job did not deliver its exact remainder once");
        var completedAfterRetry = restoredTracker.writeToNBT().getCompound("completedWork").copy();
        require(completedAfterLoad.equals(completedAfterRetry),
                "AdvancedAE escrow retry changed progress: loaded=" + completedAfterLoad
                        + ", after=" + completedAfterRetry);
        restored.logic.tickCraftingLogic(restored.energy, restored.service);
        original.logic.tickCraftingLogic(original.energy, original.service);
        require(restored.requester.deliveries == 1
                        && original.requester.accepted == 2
                        && original.storage.items.get(original.ingot) == 0
                        && original.logic.getInventory().list.get(original.ingot) == 0
                        && originalJob.bulk$outputEscrow().get(original.ingot) == 998
                        && original.dropped(Items.IRON_INGOT) == 0,
                "AdvancedAE restored or inactive old CPU redelivered output");
        helper.succeed();
    }

    // Reserve enough space for three cores beyond AdvancedAE's 7-block cluster envelope.
    @GameTest(template = "advanced_unknown_reload")
    public static void phase17UnknownAcceptanceSurvivesAdvancedClusterReload(GameTestHelper helper) throws Exception {
        var original = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        original.attachCore(new BlockPos(0, 3, 0), null);
        var provider = original.addSmeltingProvider(BlockPos.ZERO, original.raw, original.ingot);
        original.storage.items.set(original.raw, 10);
        var inputs = new KeyCounter();
        inputs.set(original.raw, 10);
        original.submit(controlledPlan(original.ingot, 10, 46, inputs,
                Map.of(provider.getAvailablePatterns().get(0), 10L)));
        var snapshot = new CompoundTag();
        original.requester.limit = 2;
        original.requester.onModulate = () -> {
            original.core.saveAdditional(snapshot);
            throw new IllegalStateException("injected AdvancedAE acceptance failure");
        };
        Phase17PersistenceGameTests.expectBlocked(original::execute);
        var link = new CompoundTag();
        original.requesterLink.writeToNBT(link);
        var afterFailure = new CompoundTag();
        original.core.saveAdditional(afterFailure);
        retireQuarantinedCore(original.core);
        int restoredIndex = 0;
        for (var saved : new CompoundTag[] {snapshot, afterFailure}) {
            var restored = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
            restored.attachCore(new BlockPos(0, 3, 16 + 16 * restoredIndex++), saved);
            restored.bindLoadedCpu();
            restored.service.addLink((CraftingLink) restored.logic.getLastLink());
            restored.service.addLink(new CraftingLink(link, restored.requester));
            restored.logic.tickCraftingLogic(restored.energy, restored.service);
            require(restored.execute() == 0 && restored.requester.accepted == 0,
                    "AdvancedAE replayed unknown acceptance after cluster reload");
            Phase17PersistenceGameTests.expectBlocked(restored.logic::cancel);
            Phase17PersistenceGameTests.expectBlocked(restored.core::breakCluster);
            require(restored.job().bulk$outputEscrow().get(restored.ingot) == 10
                            && restored.logic.getInventory().list.get(restored.ingot) == 0
                            && restored.storage.items.get(restored.ingot) == 0 && restored.dropped(Items.IRON_INGOT) == 0,
                    "AdvancedAE quarantine lost or paid out ambiguous ownership");
            retireQuarantinedCore(restored.core);
        }
        require(original.requester.accepted == 2, "fixture did not inject failure after external acceptance");
        helper.succeed();
    }

    private static void retireQuarantinedCore(AdvCraftingBlockEntity core) {
        // The test keeps the quarantined CPU in memory for assertions. Detach
        // its fixture BE before removing the block so later GameTest cleanup
        // cannot invoke a real cluster break on ambiguous ownership.
        var level = core.getLevel();
        var position = core.getBlockPos();
        core.setRemoved();
        level.removeBlockEntity(position);
        level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
        require(level.getBlockEntity(position) == null,
                "quarantined test core remained in the GameTest world");
    }

    @GameTest(template = "empty")
    public static void phase17InvalidFinalOutputRestoresEscrowBeforeStockCancellation(GameTestHelper helper)
            throws Exception {
        var original = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        var provider = original.addSmeltingProvider(BlockPos.ZERO, original.raw, original.ingot);
        original.storage.items.set(original.raw, 10);
        original.requester.limit = 0;
        var inputs = new KeyCounter();
        inputs.set(original.raw, 10);
        original.submit(controlledPlan(original.ingot, 10, 46, inputs,
                Map.of(provider.getAvailablePatterns().get(0), 10L)));
        require(original.execute() == 1, "initial AdvancedAE dispatch failed");
        var saved = new CompoundTag();
        original.logic.writeToNBT(saved);
        saved.getCompound("job").remove("finalOutput");
        // Restoring to the same type of CPU exercises stock readFromNBT's
        // invalid-final branch, which cancels before the read TAIL hook.
        original.logic.readFromNBT(saved);
        require(original.cpu.bulk$getJob() == null
                        && original.logic.getInventory().list.get(original.ingot) == 0
                        && original.storage.items.get(original.ingot) == 10,
                "stock invalid-final cancellation ran before escrow restoration");
        var marker = new CompoundTag();
        marker.putInt("dataVersion", 2); // Unknown future marker must quarantine without aborting owner load.
        saved.put("applied_causality_transaction", marker);
        original.logic.readFromNBT(saved);
        require(original.job().bulk$outputEscrow().get(original.ingot) == 10
                        && original.logic.getInventory().list.get(original.ingot) == 0
                        && original.storage.items.get(original.ingot) == 10,
                "invalid-final quarantine was lost or paid out during load");
        Phase17PersistenceGameTests.expectBlocked(original.logic::cancel);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void phase17JoblessSnapshotRetainsAdvancedCpuDuringEnumeration(GameTestHelper helper) throws Exception {
        var original = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        original.attachCore(new BlockPos(0, 3, 0), null);
        var provider = original.addSmeltingProvider(BlockPos.ZERO, original.raw, original.ingot);
        original.storage.items.set(original.raw, 10);
        var inputs = new KeyCounter();
        inputs.set(original.raw, 10);
        var plan = controlledPlan(original.ingot, 10, 46, inputs,
                Map.of(provider.getAvailablePatterns().get(0), 10L));
        var cpu = original.submitStandalone(plan);
        var snapshot = new CompoundTag();
        original.storage.onModulateInsert = () -> {
            require(!cpu.craftingLogic.hasJob(), "snapshot was not taken after job deletion");
            original.core.saveAdditional(snapshot);
        };
        require(cpu.craftingLogic.executeCrafting(1, original.service, original.energy, helper.getLevel()) == 1,
                "standalone dispatch failed");
        var restored = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        restored.attachCore(new BlockPos(0, 3, 2), snapshot);
        for (int repeat = 0; repeat < 2; repeat++) {
            require(restored.cluster.getActiveCPUs().isEmpty(), "jobless quarantine pretended to have a job");
            var saved = new CompoundTag();
            restored.core.saveAdditional(saved);
            require(saved.getList("cpuList", Tag.TAG_COMPOUND).size() == 1,
                    "stock enumeration removed quarantined CPU persistence");
        }
        var ownedField = AdvCraftingCPUCluster.class.getDeclaredField("activeCpus");
        ownedField.setAccessible(true);
        var owned = (Map<?, ?>) ownedField.get(restored.cluster);
        var restoredCpu = (AdvCraftingCPU) owned.values().iterator().next();
        restoredCpu.craftingLogic.tickCraftingLogic(restored.energy, restored.service);
        restored.storage.items.set(restored.raw, 10);
        require(!restoredCpu.craftingLogic.trySubmitJob(restored.grid, plan, IActionSource.empty(), null).successful()
                        && restored.storage.items.get(restored.raw) == 10
                        && restored.storage.items.get(restored.ingot) == 0
                        && restoredCpu.craftingLogic.getInventory().list.get(restored.ingot) == 10,
                "jobless quarantine accepted new work or paid out ambiguous inventory");
        restored.core.setRemoved();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void laterAdvancedInventoryReturnQuarantinesSnapshotsAndFailures(GameTestHelper helper)
            throws Exception {
        for (boolean failAfterAcceptance : new boolean[] {false, true}) {
            var original = joblessReturnFixture(helper);
            var snapshot = new CompoundTag();
            var failure = new IllegalStateException("later AdvancedAE return failed after acceptance");
            original.storage.insertLimit = 2;
            original.storage.onModulateInsert = () -> {
                original.logic.writeToNBT(snapshot);
                require(snapshot.getCompound("applied_causality_transaction").getBoolean("uncertain"),
                        "later AdvancedAE return callback saved unguarded inventory");
                original.logic.storeItems();
                original.logic.tickCraftingLogic(original.energy, original.service);
                require(original.storage.items.get(original.ingot) == 2, "recursive AdvancedAE return");
                if (failAfterAcceptance) throw failure;
            };
            try {
                original.logic.tickCraftingLogic(original.energy, original.service);
                require(!failAfterAcceptance, "AdvancedAE swallowed later return failure");
            } catch (IllegalStateException exception) {
                require(failAfterAcceptance && exception == failure, "original AdvancedAE exception replaced");
            }
            var afterReturn = new CompoundTag();
            original.logic.writeToNBT(afterReturn);
            require(original.logic.getInventory().list.get(original.ingot) == (failAfterAcceptance ? 10 : 8)
                            && afterReturn.contains("applied_causality_transaction") == failAfterAcceptance,
                    "AdvancedAE stable/failed remainder was not recorded exactly");
            var restored = joblessReturnFixture(helper);
            restored.logic.readFromNBT(snapshot);
            restored.storage.insertLimit = Long.MAX_VALUE;
            restored.logic.tickCraftingLogic(restored.energy, restored.service);
            restored.logic.storeItems();
            Phase17PersistenceGameTests.expectBlocked(restored.logic::cancel);
            Phase17PersistenceGameTests.expectBlocked(restored.cluster::destroy);
            restored.cluster.getActiveCPUs();
            var clusterSave = new CompoundTag();
            restored.cluster.writeToNBT(clusterSave);
            require(restored.logic.getInventory().list.get(restored.ingot) == 10
                            && restored.storage.items.get(restored.ingot) == 0
                            && clusterSave.getList("cpuList", Tag.TAG_COMPOUND).size() == 1,
                    "later AdvancedAE snapshot replayed or discarded unknown inventory");
            var checkpoint = joblessReturnFixture(helper);
            checkpoint.logic.readFromNBT(afterReturn);
            checkpoint.storage.insertLimit = Long.MAX_VALUE;
            checkpoint.logic.tickCraftingLogic(checkpoint.energy, checkpoint.service);
            if (failAfterAcceptance) {
                original.logic.storeItems();
                original.logic.tickCraftingLogic(original.energy, original.service);
                Phase17PersistenceGameTests.expectBlocked(original.cluster::destroy);
                Phase17PersistenceGameTests.expectBlocked(checkpoint.logic::cancel);
                require(original.storage.items.get(original.ingot) == 2
                                && checkpoint.storage.items.get(checkpoint.ingot) == 0
                                && checkpoint.logic.getInventory().list.get(checkpoint.ingot) == 10,
                        "failed later AdvancedAE return was retried");
            } else {
                require(checkpoint.storage.items.get(checkpoint.ingot) + original.storage.items.get(original.ingot) == 10
                                && checkpoint.logic.getInventory().list.isEmpty(),
                        "stable AdvancedAE remainder was not returned exactly once");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void laterAdvancedInventoryReturnDefersBlockBreak(GameTestHelper helper) throws Exception {
        for (long acceptance : new long[] {0, 2, 10}) {
            var f = joblessReturnFixture(helper);
            var core = f.attachCore(new BlockPos(0, 3, 0), null);
            long previousDrops = f.dropped(Items.IRON_INGOT);
            f.storage.insertLimit = acceptance;
            f.storage.onModulateInsert = () -> {
                core.breakCluster();
                require(f.logic.getInventory().list.get(f.ingot) == 10
                                && f.dropped(Items.IRON_INGOT) == previousDrops,
                        "AdvancedAE broke CPU before the later return settled");
            };
            f.logic.tickCraftingLogic(f.energy, f.service);
            require(f.storage.items.get(f.ingot) + f.dropped(Items.IRON_INGOT) - previousDrops == 10
                            && f.logic.getInventory().list.isEmpty(),
                    "AdvancedAE later-return teardown duplicated or lost output");
            core.breakCluster();
            require(f.storage.items.get(f.ingot) + f.dropped(Items.IRON_INGOT) - previousDrops == 10,
                    "AdvancedAE later-return repeated break duplicated output");
        }
        helper.succeed();
    }

    private static Fixture joblessReturnFixture(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, SOURCE_VALID_CAPACITY, 0);
        var provider = f.addSmeltingProvider(BlockPos.ZERO, f.raw, f.ingot);
        f.storage.items.set(f.raw, 10);
        f.storage.insertLimit = 0;
        f.requester.limit = 0;
        var inputs = new KeyCounter();
        inputs.set(f.raw, 10);
        f.submit(controlledPlan(f.ingot, 10, 46, inputs,
                Map.of(provider.getAvailablePatterns().get(0), 10L)));
        require(f.execute() == 1, "AdvancedAE initial dispatch failed");
        f.logic.cancel();
        require(f.cpu.bulk$getJob() == null && f.logic.getInventory().list.get(f.ingot) == 10
                        && f.storage.items.get(f.ingot) == 0,
                "AdvancedAE full network did not retain jobless output");
        return f;
    }

    private static IPatternDetails processingPattern(
            GameTestHelper helper, GenericStack[] inputs, GenericStack[] outputs) {
        var pattern = PatternDetailsHelper.decodePattern(
                PatternDetailsHelper.encodeProcessingPattern(inputs, outputs), helper.getLevel());
        if (pattern == null) {
            throw new GameTestAssertException("could not decode controlled processing pattern");
        }
        return pattern;
    }

    private static ICraftingPlan controlledPlan(
            AEKey finalKey,
            long finalAmount,
            long bytes,
            KeyCounter usedItems,
            Map<IPatternDetails, Long> tasks) {
        return new CraftingPlan(
                new GenericStack(finalKey, finalAmount),
                bytes,
                false,
                tasks.size() > 1,
                usedItems,
                new KeyCounter(),
                new KeyCounter(),
                tasks);
    }

    private static long taskRemaining(IBulkJobBridge job, IPatternDetails pattern) {
        for (var task : job.bulk$tasks()) {
            if (task.pattern().equals(pattern)) {
                return task.remaining();
            }
        }
        return 0;
    }

    private static IBulkJobBridge requireJob(IBulkCpuBridge cpu) {
        var job = cpu.bulk$getJob();
        require(job != null, "expected an active AdvancedAE job");
        return job;
    }

    private static void requireBulkReady(Fixture fixture, IBulkJobBridge job, int expectedBulkTasks) {
        int ready = 0;
        int rawProviders = 0;
        int bulkProviders = 0;
        int busyProviders = 0;
        int models = 0;
        for (var task : job.bulk$tasks()) {
            for (var provider : fixture.service.getProviders(task.pattern())) {
                rawProviders++;
                if (provider instanceof IBulkCraftingProvider bulk) {
                    bulkProviders++;
                    if (provider.isBusy()) {
                        busyProviders++;
                    }
                    if (bulk.getVerifiedProcessModel(task.pattern()).isPresent()) {
                        models++;
                    }
                }
                if (provider instanceof IBulkCraftingProvider bulk
                        && !provider.isBusy()
                        && bulk.getVerifiedProcessModel(task.pattern()).isPresent()) {
                    ready++;
                    break;
                }
            }
        }
        require(ready == expectedBulkTasks,
                "AdvancedAE job/provider ready=" + ready
                        + ", expected=" + expectedBulkTasks
                        + ", raw=" + rawProviders
                        + ", bulk=" + bulkProviders
                        + ", busy=" + busyProviders
                        + ", models=" + models);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final AEItemKey raw = AEItemKey.of(Items.RAW_IRON);
        final AEItemKey ingot = AEItemKey.of(Items.IRON_INGOT);
        final TestStorage storage = new TestStorage();
        final List<IGridNode> providerNodes = new ArrayList<>();
        final Requester requester = new Requester();
        final BulkDispatchBudgetService dispatchBudget = new BulkDispatchBudgetService();
        final IGrid grid;
        final IEnergyService energy;
        final CraftingService service;
        final TestAdvancedCluster cluster;
        AdvCraftingBlockEntity core;
        AdvCraftingCPU advancedCpu;
        AdvCraftingCPULogic logic;
        IBulkCpuBridge cpu;
        ICraftingLink requesterLink;

        Fixture(GameTestHelper helper, long capacity, int coProcessors) {
            this.helper = helper;
            var storageService = proxy(IStorageService.class, (method, args) -> switch (method) {
                case "getInventory" -> storage;
                case "getCachedInventory" -> storage.items;
                case "addGlobalStorageProvider" -> null;
                default -> throw new AssertionError("unexpected storage call " + method);
            });
            energy = proxy(IEnergyService.class, (method, args) -> {
                if (method.equals("extractAEPower")) {
                    return args[0];
                }
                throw new AssertionError("unexpected energy call " + method);
            });
            grid = proxy(IGrid.class, (method, args) -> switch (method) {
                case "getService" -> args[0] == IBulkDispatchBudget.class ? dispatchBudget : null;
                case "getStorageService" -> storageService;
                case "getCraftingService" -> craftingService();
                case "postEvent" -> null;
                default -> throw new AssertionError("unexpected grid call " + method);
            });
            service = new CraftingService(grid, storageService, energy);
            cluster = new TestAdvancedCluster(helper.getLevel(), grid, capacity, coProcessors);
        }

        private CraftingService craftingService() {
            return service;
        }

        SingularPatternProviderBlockEntity addSmeltingProvider(
                BlockPos position, AEKey input, AEKey output) throws Exception {
            var provider = new SingularPatternProviderBlockEntity(
                    position, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get().defaultBlockState());
            provider.setLevel(helper.getLevel());
            Phase11StandardAe2GameTests.setActiveNode(
                    provider, provider.getMainNode(), helper, grid, true);
            provider.getInternalInventory().setItemDirect(0, PatternDetailsHelper.encodeProcessingPattern(
                    new GenericStack[] { new GenericStack(input, 1) },
                    new GenericStack[] { new GenericStack(output, 1) }));
            require(provider.getAvailablePatterns().size() == 1,
                    "real smelting provider did not verify/advertise to AdvancedAE");
            register(provider);
            return provider;
        }

        void register(ICraftingProvider provider) {
            var node = proxy(IGridNode.class, (method, args) -> switch (method) {
                case "getService" -> args[0] == ICraftingProvider.class ? provider : null;
                case "getOwner" -> provider;
                default -> throw new AssertionError("unexpected provider node call " + method);
            });
            providerNodes.add(node);
            service.addNode(node, null);
        }

        void submit(ICraftingPlan plan) {
            var result = cluster.submitJob(grid, plan, IActionSource.empty(), requester);
            require(result.successful(), "AdvancedAE submission failed: " + result.errorCode());
            requesterLink = result.link();
            bindLoadedCpu();
        }

        AdvCraftingCPU submitStandalone(ICraftingPlan plan) {
            Set<AdvCraftingCPU> before = Set.copyOf(cluster.getActiveCPUs());
            var result = cluster.submitJob(grid, plan, IActionSource.empty(), null);
            require(result.successful() && result.link() == null,
                    "AdvancedAE standalone submission failed: " + result.errorCode());
            var added = cluster.getActiveCPUs().stream()
                    .filter(candidate -> !before.contains(candidate))
                    .toList();
            require(added.size() == 1, "expected exactly one new standalone AdvancedAE CPU");
            return added.get(0);
        }

        AdvCraftingBlockEntity attachCore(BlockPos relativePosition, CompoundTag savedState) throws Exception {
            Block block = quantumCoreBlock();
            helper.setBlock(relativePosition, block);
            core = (AdvCraftingBlockEntity) helper.getBlockEntity(relativePosition);
            if (savedState != null) {
                core.loadTag(savedState);
            }
            core.updateStatus(cluster);
            var add = AdvCraftingCPUCluster.class.getDeclaredMethod(
                    "addBlockEntity", AdvCraftingBlockEntity.class);
            add.setAccessible(true);
            add.invoke(cluster, core);
            Phase11StandardAe2GameTests.setActiveNode(
                    core, core.getMainNode(), helper, grid, true);
            cluster.useProductionActivity = true;
            var done = AdvCraftingCPUCluster.class.getDeclaredMethod("done");
            done.setAccessible(true);
            done.invoke(cluster);
            return core;
        }

        void bindLoadedCpu() {
            var cpus = cluster.getActiveCPUs();
            require(cpus.size() == 1, "expected one active AdvancedAE CPU");
            advancedCpu = cpus.get(0);
            logic = advancedCpu.craftingLogic;
            cpu = (IBulkCpuBridge) (Object) logic;
        }

        IBulkJobBridge job() {
            var current = cpu.bulk$getJob();
            require(current != null, "expected an active AdvancedAE job");
            return current;
        }

        int execute() {
            return logic.executeCrafting(1, service, energy, helper.getLevel());
        }

        long dropped(Item item) {
            require(core != null, "AdvancedAE fixture has no attached core");
            return helper.getLevel().getEntitiesOfClass(
                            ItemEntity.class, new AABB(core.getBlockPos()).inflate(2))
                    .stream()
                    .filter(entity -> entity.getItem().is(item))
                    .mapToLong(entity -> entity.getItem().getCount())
                    .sum();
        }
    }

    private static final class TestAdvancedCluster extends AdvCraftingCPUCluster {
        private final Level level;
        private final IGrid grid;
        private final long capacity;
        private final int coProcessors;
        private boolean useProductionActivity;

        TestAdvancedCluster(Level level, IGrid grid, long capacity, int coProcessors) {
            super(BlockPos.ZERO, BlockPos.ZERO);
            this.level = level;
            this.grid = grid;
            this.capacity = capacity;
            this.coProcessors = coProcessors;
        }

        @Override
        public long getAvailableStorage() {
            return capacity;
        }

        @Override
        public int getCoProcessors() {
            return coProcessors;
        }

        @Override
        public boolean isActive() {
            return useProductionActivity ? super.isActive() : true;
        }

        @Override
        public Level getLevel() {
            return level;
        }

        @Override
        public IGrid getGrid() {
            return grid;
        }

        @Override
        public IActionSource getSrc() {
            return IActionSource.empty();
        }

        @Override
        public void markDirty() {
        }
    }

    private static final class NormalProvider implements ICraftingProvider {
        private final IPatternDetails pattern;
        private int pushes;

        NormalProvider(IPatternDetails pattern) {
            this.pattern = pattern;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.of(pattern);
        }

        @Override
        public boolean isBusy() {
            return false;
        }

        @Override
        public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) {
            require(details.equals(pattern) && inputs.length == pattern.getInputs().length,
                    "normal provider received a different pattern/input shape");
            pushes++;
            return true;
        }
    }

    private static final class Requester implements ICraftingRequester {
        private long limit = Long.MAX_VALUE;
        private long accepted;
        private int simulations;
        private int deliveries;
        private Runnable onModulate = () -> {};

        @Override
        public long insertCraftedItems(ICraftingLink link, AEKey key, long amount, Actionable mode) {
            if (mode == Actionable.SIMULATE) {
                simulations++;
                return amount;
            }
            deliveries++;
            long inserted = Math.min(amount, limit);
            accepted += inserted;
            onModulate.run();
            return inserted;
        }

        @Override
        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            return ImmutableSet.of();
        }

        @Override
        public void jobStateChange(ICraftingLink link) {
        }

        @Override
        public IGridNode getActionableNode() {
            return null;
        }
    }

    private static final class TestStorage implements MEStorage {
        private final KeyCounter items = new KeyCounter();
        private Runnable onModulateInsert;
        private long insertLimit = Long.MAX_VALUE;

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            long extracted = Math.min(amount, items.get(key));
            if (mode == Actionable.MODULATE) {
                items.remove(key, extracted);
            }
            return extracted;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            long inserted = Math.min(amount, insertLimit);
            if (mode == Actionable.MODULATE) {
                items.add(key, inserted);
                var callback = onModulateInsert;
                onModulateInsert = null;
                if (callback != null) {
                    callback.run();
                }
            }
            return inserted;
        }

        @Override
        public Component getDescription() {
            return Component.literal("Phase 14 controlled network storage");
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] arguments);
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] { type },
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Phase14 " + type.getSimpleName();
                    default -> invocation.call(method.getName(), args);
                }));
    }

    private static Block quantumCoreBlock() throws ReflectiveOperationException {
        var definition = Class.forName("net.pedroksl.advanced_ae.common.definitions.AAEBlocks")
                .getField("QUANTUM_CORE")
                .get(null);
        return (Block) definition.getClass().getMethod("block").invoke(definition);
    }
}
