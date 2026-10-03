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

/** One real-recipe Fluid dispatch owns both Gas outputs through AE2 and appmek. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalSeparatingCpuGameTests {
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);

    private ChemicalSeparatingCpuGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInTaggedWaterRunsOneExactDispatch(GameTestHelper helper)
            throws Exception {
        MekanismKey hydrogen = gas("hydrogen");
        MekanismKey oxygen = gas("oxygen");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(WATER, 2)},
                new GenericStack[]{new GenericStack(hydrogen, 2), new GenericStack(oxygen, 1)}),
                helper.getLevel());
        require(pattern != null, "built-in tagged Water Pattern did not decode");
        var context = new PatternContext(helper.getLevel(), pattern);
        var recipe = helper.getLevel().getRecipeManager()
                .byKey(ResourceLocation.fromNamespaceAndPath("mekanism", "separator/water"))
                .orElseThrow();
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "built-in tagged Water recipe was not Core-verified");
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        fixture.storage.items.set(WATER, 200);
        fixture.requester.limit = 20;
        var plan = fixture.calculate(hydrogen, 200);
        require(!plan.simulation(), "AE2 did not plan the built-in tagged Water recipe");
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && fixture.storage.items.get(WATER) == 0
                        && fixture.requester.accepted == 20
                        && fixture.job().bulk$outputEscrow().get(hydrogen) == 180
                        && fixture.logic.getInventory().list.get(oxygen) == 100,
                "built-in Water dispatch did not own both Gas outputs exactly");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void twoGasOutputsSurvivePartialAcceptanceCancelAndReconnect(GameTestHelper helper)
            throws Exception {
        MekanismKey hydrogen = gas("hydrogen");
        MekanismKey oxygen = gas("oxygen");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(WATER, 3)},
                new GenericStack[]{new GenericStack(hydrogen, 5), new GenericStack(oxygen, 7)}),
                helper.getLevel());
        require(pattern != null, "two-Gas separating Pattern did not decode");
        var context = new PatternContext(helper.getLevel(), pattern);
        var recipe = helper.getLevel().getRecipeManager().byKey(id("separating")).orElseThrow();
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "real two-Gas separating recipe was not Core-verified");

        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        fixture.storage.items.set(WATER, 300);
        var oxygenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 700);
        var hydrogenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 0);
        fixture.networkStorage = new FluidAndTwoGasStorage(fixture.storage,
                hydrogen, hydrogenStore.network(), oxygen, oxygenStore.network());
        fixture.requester.limit = 50;
        var plan = fixture.calculate(hydrogen, 500);
        require(!plan.simulation(), "AE2 did not plan the real separating recipe: missing="
                + plan.missingItems() + ", used=" + plan.usedItems()
                + ", patterns=" + plan.patternTimes());
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && fixture.storage.items.get(WATER) == 0
                        && fixture.requester.accepted == 50,
                "one separating dispatch changed Fluid consumption or final Gas acceptance");
        var job = fixture.job();
        require(job.bulk$outputEscrow().get(hydrogen) == 450
                        && job.bulk$outputEscrow().get(oxygen) == 0
                        && job.bulk$getRemainingFinalAmount() == 450
                        && job.bulk$getWaitingFor(hydrogen) == 0
                        && job.bulk$getWaitingFor(oxygen) == 0,
                "paired separating outputs were not owned exactly once");
        require(fixture.logic.getInventory().list.get(oxygen)
                        + oxygenStore.inventory().getAmount(0) == 700,
                "separating byproduct was lost before network insertion");
        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(hydrogen) == 450,
                "separating final Gas escrow changed on save/load");
        restored.networkStorage = new FluidAndTwoGasStorage(restored.storage,
                hydrogen, hydrogenStore.network(), oxygen, oxygenStore.network());
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(hydrogen) == 450
                        && hydrogenStore.inventory().getAmount(0) == 0
                        && restored.logic.getInventory().list.get(oxygen)
                        + oxygenStore.inventory().getAmount(0) == 700,
                "cancel lost final Gas rejected by appmek storage");
        restored.logic.storeItems();
        require(oxygenStore.inventory().getAmount(0) == 700
                        && restored.logic.getInventory().list.get(oxygen) == 0,
                "appmek bridge did not receive the exact Gas byproduct after cancel");
        hydrogenStore.inventory().setCapacity(MekanismKeyType.TYPE, 200);
        restored.logic.storeItems();
        require(hydrogenStore.inventory().getAmount(0) == 200
                        && restored.logic.getInventory().list.get(hydrogen) == 250,
                "partial separating Gas return changed ownership");
        hydrogenStore.inventory().setCapacity(MekanismKeyType.TYPE, 450);
        restored.logic.storeItems();
        require(hydrogenStore.inventory().getAmount(0) == 450
                        && restored.logic.getInventory().list.get(hydrogen) == 0,
                "retry duplicated or lost final separating Gas");

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectOxygenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 700);
        reconnected.networkStorage = new FluidAndTwoGasStorage(reconnected.storage,
                hydrogen, ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 0).network(),
                oxygen, reconnectOxygenStore.network());
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(hydrogen) == 450,
                "disconnected separating requester lost Gas escrow");
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "separating requester identity changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(hydrogen) == 450,
                "zero acceptance lost final separating Gas");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == 450
                        && fixture.requester.accepted + reconnected.requester.accepted == 500
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && reconnectOxygenStore.inventory().getAmount(0)
                        + reconnected.logic.getInventory().list.get(oxygen) == 700
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "separating reconnect repeated Gas or progress");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sameGasOutputsAggregateBeforeOneDispatch(GameTestHelper helper)
            throws Exception {
        MekanismKey hydrogen = gas("hydrogen");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(WATER, 4)},
                new GenericStack[]{new GenericStack(hydrogen, 5), new GenericStack(hydrogen, 7)}),
                helper.getLevel());
        require(pattern != null && pattern.getOutputs().length == 1,
                "same-Gas separating Pattern did not aggregate");
        var context = new PatternContext(helper.getLevel(), pattern);
        var recipe = helper.getLevel().getRecipeManager()
                .byKey(id("separating_same_gas")).orElseThrow();
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "same-Gas separating recipe was not Core-verified");
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        fixture.storage.items.set(WATER, 400);
        fixture.requester.limit = 100;
        var plan = fixture.calculate(hydrogen, 1_200);
        require(!plan.simulation(), "AE2 did not plan same-Gas separating");
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && fixture.storage.items.get(WATER) == 0
                        && fixture.requester.accepted == 100
                        && fixture.job().bulk$outputEscrow().get(hydrogen) == 1_100,
                "same-Gas separating dispatch or escrow was not exact");
        helper.succeed();
    }

    private static MekanismKey gas(String path) {
        return MekanismKey.of(MekanismAPI.gasRegistry()
                .getValue(ResourceLocation.fromNamespaceAndPath("mekanism", path)).getStack(1));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("pack_test", path);
    }

    private record FluidAndTwoGasStorage(Phase11StandardAe2GameTests.TestStorage fluid,
            AEKey leftKey, MEStorage left, AEKey rightKey, MEStorage right) implements MEStorage {
        private MEStorage storageFor(AEKey key) {
            return key.equals(leftKey) ? left : key.equals(rightKey) ? right : fluid;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            return storageFor(key).extract(key, amount, mode, source);
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            return storageFor(key).insert(key, amount, mode, source);
        }

        @Override
        public Component getDescription() {
            return Component.literal("Fluid and two appmek Gas stores");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
