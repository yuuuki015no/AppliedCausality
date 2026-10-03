package dev.appliedcausality.gametest;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingLink;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ProcessModelValidator;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import me.ramidzkh.mekae2.ae2.MekanismKeyType;
import mekanism.api.MekanismAPI;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Each rotary direction owns one exact output across AE2 and appmek storage. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalRotaryCpuGameTests {
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);
    private static final AEFluidKey LAVA = AEFluidKey.of(Fluids.LAVA);

    private ChemicalRotaryCpuGameTests() {
    }

    @GameTest(template = "empty")
    public static void fluidToGasOwnsEscrowAcrossRestartAndCancel(GameTestHelper helper)
            throws Exception {
        var hydrogen = gas("hydrogen");
        var pattern = pattern(helper, WATER, 7, hydrogen, 11);
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, verified(helper, pattern)));
        fixture.storage.items.set(WATER, 700);
        var hydrogenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 0);
        fixture.networkStorage = new RoutedStorage(fixture.storage, hydrogen, hydrogenStore.network(), null);
        fixture.requester.limit = 100;
        var plan = fixture.calculate(hydrogen, 1_100);
        require(!plan.simulation() && totalPatterns(plan) == 100,
                "AE2 did not plan 100 Fluid-to-Gas rotary operations: patterns=" + plan.patternTimes());
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && fixture.storage.items.get(WATER) == 0
                        && fixture.requester.accepted == 100
                        && fixture.job().bulk$outputEscrow().get(hydrogen) == 1_000
                        && fixture.job().bulk$getRemainingFinalAmount() == 1_000
                        && fixture.job().bulk$getWaitingFor(hydrogen) == 0,
                "Fluid-to-Gas rotary did not dispatch or own exact Gas output");

        var saved = new CompoundTag();
        var savedLink = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        fixture.requesterLink.writeToNBT(savedLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        restored.networkStorage = new RoutedStorage(restored.storage,
                hydrogen, hydrogenStore.network(), null);
        require(restored.job().bulk$outputEscrow().get(hydrogen) == 1_000,
                "Fluid-to-Gas rotary Gas escrow changed on restart");
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(hydrogen) == 1_000
                        && hydrogenStore.inventory().getAmount(0) == 0,
                "rotary cancel lost Gas rejected by appmek bridge");
        hydrogenStore.inventory().setCapacity(MekanismKeyType.TYPE, 400);
        restored.logic.storeItems();
        require(hydrogenStore.inventory().getAmount(0) == 400
                        && restored.logic.getInventory().list.get(hydrogen) == 600,
                "partial rotary Gas return changed ownership");
        hydrogenStore.inventory().setCapacity(MekanismKeyType.TYPE, 1_000);
        restored.logic.storeItems();
        require(hydrogenStore.inventory().getAmount(0) == 1_000
                        && restored.logic.getInventory().list.get(hydrogen) == 0,
                "rotary Gas return retry duplicated output");
        assertReconnect(helper, fixture, saved, savedLink, hydrogen, 1_000, 1_100);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void gasToFluidOwnsEscrowAcrossRestartAndCancel(GameTestHelper helper)
            throws Exception {
        var oxygen = gas("oxygen");
        var pattern = pattern(helper, oxygen, 13, LAVA, 17);
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, verified(helper, pattern)));
        var oxygenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 1_300);
        oxygenStore.inventory().setStack(0, new GenericStack(oxygen, 1_300));
        oxygenStore.network().getAvailableStacks(fixture.storage.items);
        fixture.networkStorage = new RoutedStorage(fixture.storage, oxygen, oxygenStore.network(), LAVA);
        fixture.requester.limit = 100;
        var plan = fixture.calculate(LAVA, 1_700);
        require(!plan.simulation() && totalPatterns(plan) == 100,
                "AE2 did not plan 100 Gas-to-Fluid rotary operations: patterns=" + plan.patternTimes());
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && oxygenStore.inventory().getAmount(0) == 0
                        && fixture.requester.accepted == 100
                        && fixture.job().bulk$outputEscrow().get(LAVA) == 1_600
                        && fixture.job().bulk$getRemainingFinalAmount() == 1_600
                        && fixture.job().bulk$getWaitingFor(LAVA) == 0,
                "Gas-to-Fluid rotary did not consume Gas or own exact Fluid output");

        var saved = new CompoundTag();
        var savedLink = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        fixture.requesterLink.writeToNBT(savedLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        var returned = new RoutedStorage(restored.storage, oxygen, oxygenStore.network(), LAVA);
        returned.fluidCapacity = 0;
        restored.networkStorage = returned;
        require(restored.job().bulk$outputEscrow().get(LAVA) == 1_600,
                "Gas-to-Fluid rotary Fluid escrow changed on restart");
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(LAVA) == 1_600
                        && restored.storage.items.get(LAVA) == 0,
                "rotary cancel lost rejected Fluid");
        returned.fluidCapacity = 500;
        restored.logic.storeItems();
        require(restored.storage.items.get(LAVA) == 500
                        && restored.logic.getInventory().list.get(LAVA) == 1_100,
                "partial rotary Fluid return changed ownership");
        returned.fluidCapacity = 1_600;
        restored.logic.storeItems();
        require(restored.storage.items.get(LAVA) == 1_600
                        && restored.logic.getInventory().list.get(LAVA) == 0,
                "rotary Fluid return retry duplicated output");
        assertReconnect(helper, fixture, saved, savedLink, LAVA, 1_600, 1_700);
        helper.succeed();
    }

    private static void assertReconnect(GameTestHelper helper, Phase11StandardAe2GameTests.Fixture original,
            CompoundTag saved, CompoundTag savedLink, AEKey output, long remainder, long total)
            throws Exception {
        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var job = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(job.bulk$outputEscrow().get(output) == remainder,
                "disconnected rotary requester lost escrow");
        var requesterLink = new CraftingLink(savedLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "rotary requester identity changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(job.bulk$outputEscrow().get(output) == remainder,
                "zero acceptance lost rotary escrow");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == remainder
                        && original.requester.accepted + remainder == total
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && job.bulk$outputEscrow().isEmpty()
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "rotary reconnect duplicated output or progress");
    }

    private static appeng.api.crafting.IPatternDetails pattern(GameTestHelper helper,
            AEKey input, long inputAmount, AEKey output, long outputAmount) {
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, inputAmount)},
                new GenericStack[]{new GenericStack(output, outputAmount)}), helper.getLevel());
        require(pattern != null, "rotary Pattern did not decode");
        return pattern;
    }

    private static dev.appliedcausality.internal.validation.VerifiedProcessModel verified(
            GameTestHelper helper, appeng.api.crafting.IPatternDetails pattern) {
        var context = new PatternContext(helper.getLevel(), pattern);
        var recipe = helper.getLevel().getRecipeManager()
                .byKey(ResourceLocation.fromNamespaceAndPath("pack_test", "rotary")).orElseThrow();
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var result = ProcessModelValidator.validate(new ProcessCandidate(
                MekanismRecipeAdapter.ID, 0, candidate), context);
        require(result.diagnostic() == ValidationDiagnostic.VALID,
                "real rotary direction was not Core-verified");
        return result.model().orElseThrow();
    }

    private static long totalPatterns(appeng.api.networking.crafting.ICraftingPlan plan) {
        return plan.patternTimes().values().stream().mapToLong(Long::longValue).sum();
    }

    private static MekanismKey gas(String path) {
        return MekanismKey.of(MekanismAPI.gasRegistry().getValue(
                ResourceLocation.fromNamespaceAndPath("mekanism", path)).getStack(1));
    }

    private static final class RoutedStorage implements MEStorage {
        private final Phase11StandardAe2GameTests.TestStorage items;
        private final AEKey gasKey;
        private final MEStorage gasStorage;
        private final AEFluidKey fluidOutput;
        private long fluidCapacity = Long.MAX_VALUE;

        private RoutedStorage(Phase11StandardAe2GameTests.TestStorage items,
                AEKey gasKey, MEStorage gasStorage, AEFluidKey fluidOutput) {
            this.items = items;
            this.gasKey = gasKey;
            this.gasStorage = gasStorage;
            this.fluidOutput = fluidOutput;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            return key.equals(gasKey) ? gasStorage.extract(key, amount, mode, source)
                    : items.extract(key, amount, mode, source);
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (key.equals(gasKey)) {
                return gasStorage.insert(key, amount, mode, source);
            }
            if (key.equals(fluidOutput)) {
                long accepted = Math.min(amount, Math.max(0, fluidCapacity - items.items.get(key)));
                return items.insert(key, accepted, mode, source);
            }
            return items.insert(key, amount, mode, source);
        }

        @Override
        public Component getDescription() {
            return Component.literal("Rotary Fluid storage and appmek Gas bridge");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
