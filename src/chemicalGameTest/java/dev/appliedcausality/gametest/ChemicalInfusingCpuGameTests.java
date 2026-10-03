package dev.appliedcausality.gametest;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.security.IActionSource;
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
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Owns both real-recipe Gas inputs and the output through AE2 and the appmek bridge. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalInfusingCpuGameTests {
    private static final ResourceLocation PACK_ID = id("pack_test", "chemical_infusing");

    private ChemicalInfusingCpuGameTests() {
    }

    @GameTest(template = "empty")
    public static void twoGasRecipeOwnsOutputAcrossCancelAndRestart(GameTestHelper helper) throws Exception {
        var recipe = helper.getLevel().getRecipeManager().byKey(PACK_ID).orElseThrow();
        var hydrogen = key("hydrogen");
        var chlorine = key("chlorine");
        var oxygen = key("oxygen");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(chlorine, 3), new GenericStack(hydrogen, 2)},
                new GenericStack[]{new GenericStack(oxygen, 17)}), helper.getLevel());
        require(pattern != null, "two-Gas CPU Pattern did not decode");
        var context = new PatternContext(helper.getLevel(), pattern);
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "real two-Gas recipe was not Core-verified");
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        var hydrogenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 200);
        var chlorineStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 300);
        hydrogenStore.inventory().setStack(0, new GenericStack(hydrogen, 200));
        chlorineStore.inventory().setStack(0, new GenericStack(chlorine, 300));
        fixture.networkStorage = new TwoGasStorage(hydrogen, hydrogenStore.network(),
                chlorine, chlorineStore.network());
        hydrogenStore.network().getAvailableStacks(fixture.storage.items);
        chlorineStore.network().getAvailableStacks(fixture.storage.items);
        require(fixture.storage.items.get(hydrogen) == 200
                        && fixture.storage.items.get(chlorine) == 300,
                "appmek bridge changed the two planning input quantities");
        fixture.requester.limit = 100;
        var plan = fixture.calculate(oxygen, 1_700);
        require(!plan.simulation(), "AE2 did not plan the two-Gas recipe");
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1
                        && hydrogenStore.inventory().getAmount(0) == 0
                        && chlorineStore.inventory().getAmount(0) == 0
                        && fixture.requester.accepted == 100,
                "one CPU dispatch did not consume both Gas inputs exactly");
        var job = fixture.job();
        require(job.bulk$outputEscrow().get(oxygen) == 1_600
                        && job.bulk$getRemainingFinalAmount() == 1_600
                        && job.bulk$getWaitingFor(oxygen) == 0,
                "two-Gas output was not owned once by escrow");

        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(oxygen) == 1_600,
                "two-Gas output escrow changed on save/load");
        var returned = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 0);
        restored.networkStorage = returned.network();
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(oxygen) == 1_600
                        && returned.inventory().getAmount(0) == 0,
                "cancel lost output rejected by appmek Gas storage");
        returned.inventory().setCapacity(MekanismKeyType.TYPE, 600);
        restored.logic.storeItems();
        require(returned.inventory().getAmount(0) == 600
                        && restored.logic.getInventory().list.get(oxygen) == 1_000,
                "partial Gas return changed two-Gas output ownership");
        returned.inventory().setCapacity(MekanismKeyType.TYPE, 1_700);
        restored.logic.storeItems();
        require(returned.inventory().getAmount(0) == 1_600
                        && restored.logic.getInventory().list.get(oxygen) == 0,
                "retry lost or duplicated two-Gas output");

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(oxygen) == 1_600,
                "disconnected requester lost two-Gas escrow");
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "two-Gas requester link changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(oxygen) == 1_600,
                "zero-acceptance requester lost two-Gas escrow");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == 1_600
                        && fixture.requester.accepted + reconnected.requester.accepted == 1_700
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "two-Gas requester reconnect repeated output or progress");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sameGasRecipeConsumesCheckedAggregateInOneDispatch(GameTestHelper helper)
            throws Exception {
        var recipe = helper.getLevel().getRecipeManager()
                .byKey(id("pack_test", "chemical_infusing_same_gas")).orElseThrow();
        var hydrogen = key("hydrogen");
        var waterVapor = key("water_vapor");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(hydrogen, 2), new GenericStack(hydrogen, 3)},
                new GenericStack[]{new GenericStack(waterVapor, 7)}), helper.getLevel());
        require(pattern != null && pattern.getInputs().length == 1,
                "same-Gas CPU Pattern was not aggregated");
        var context = new PatternContext(helper.getLevel(), pattern);
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "same-Gas recipe was not Core-verified");
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        var input = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 500);
        input.inventory().setStack(0, new GenericStack(hydrogen, 500));
        fixture.networkStorage = input.network();
        input.network().getAvailableStacks(fixture.storage.items);
        fixture.requester.limit = 100;
        var plan = fixture.calculate(waterVapor, 700);
        require(!plan.simulation(), "AE2 did not plan the same-Gas recipe");
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && input.inventory().getAmount(0) == 0
                        && fixture.requester.accepted == 100
                        && fixture.job().bulk$outputEscrow().get(waterVapor) == 600,
                "same-Gas one-dispatch quantities or escrow changed");
        helper.succeed();
    }

    private static MekanismKey key(String path) {
        return MekanismKey.of(MekanismAPI.gasRegistry().getValue(id("mekanism", path)).getStack(1));
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record TwoGasStorage(AEKey leftKey, MEStorage left, AEKey rightKey, MEStorage right)
            implements MEStorage {
        private MEStorage storageFor(AEKey key) {
            return key.equals(leftKey) ? left : key.equals(rightKey) ? right : null;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            MEStorage storage = storageFor(key);
            return storage == null ? 0 : storage.extract(key, amount, mode, source);
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            MEStorage storage = storageFor(key);
            return storage == null ? 0 : storage.insert(key, amount, mode, source);
        }

        @Override
        public Component getDescription() {
            return Component.literal("Two bridged Gas inputs");
        }
    }
}
