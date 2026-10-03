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

/** Owns both real-recipe Pigment inputs and the output through AE2 and the appmek bridge. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PigmentMixingCpuGameTests {
    private static final ResourceLocation PACK_ID = id("pack_test", "pigment_mixing");

    private PigmentMixingCpuGameTests() {
    }

    @GameTest(template = "empty")
    public static void twoPigmentRecipeOwnsOutputAcrossCancelAndRestart(GameTestHelper helper) throws Exception {
        var recipe = helper.getLevel().getRecipeManager().byKey(PACK_ID).orElseThrow();
        var red = key("red");
        var yellow = key("yellow");
        var orange = key("orange");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(yellow, 3), new GenericStack(red, 2)},
                new GenericStack[]{new GenericStack(orange, 17)}), helper.getLevel());
        require(pattern != null, "two-Pigment CPU Pattern did not decode");
        var context = new PatternContext(helper.getLevel(), pattern);
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "real two-Pigment recipe was not Core-verified");
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        var redStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.PIGMENT, 200);
        var yellowStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.PIGMENT, 300);
        redStore.inventory().setStack(0, new GenericStack(red, 200));
        yellowStore.inventory().setStack(0, new GenericStack(yellow, 300));
        fixture.networkStorage = new TwoPigmentStorage(red, redStore.network(),
                yellow, yellowStore.network());
        redStore.network().getAvailableStacks(fixture.storage.items);
        yellowStore.network().getAvailableStacks(fixture.storage.items);
        require(fixture.storage.items.get(red) == 200
                        && fixture.storage.items.get(yellow) == 300,
                "appmek bridge changed the two planning input quantities");
        fixture.requester.limit = 100;
        var plan = fixture.calculate(orange, 1_700);
        require(!plan.simulation(), "AE2 did not plan the two-Pigment recipe");
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1
                        && redStore.inventory().getAmount(0) == 0
                        && yellowStore.inventory().getAmount(0) == 0
                        && fixture.requester.accepted == 100,
                "one CPU dispatch did not consume both Pigment inputs exactly");
        var job = fixture.job();
        require(job.bulk$outputEscrow().get(orange) == 1_600
                        && job.bulk$getRemainingFinalAmount() == 1_600
                        && job.bulk$getWaitingFor(orange) == 0,
                "two-Pigment output was not owned once by escrow");

        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(orange) == 1_600,
                "two-Pigment output escrow changed on save/load");
        var returned = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.PIGMENT, 0);
        restored.networkStorage = returned.network();
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(orange) == 1_600
                        && returned.inventory().getAmount(0) == 0,
                "cancel lost output rejected by appmek Pigment storage");
        returned.inventory().setCapacity(MekanismKeyType.TYPE, 600);
        restored.logic.storeItems();
        require(returned.inventory().getAmount(0) == 600
                        && restored.logic.getInventory().list.get(orange) == 1_000,
                "partial Pigment return changed two-Pigment output ownership");
        returned.inventory().setCapacity(MekanismKeyType.TYPE, 1_700);
        restored.logic.storeItems();
        require(returned.inventory().getAmount(0) == 1_600
                        && restored.logic.getInventory().list.get(orange) == 0,
                "retry lost or duplicated two-Pigment output");

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(orange) == 1_600,
                "disconnected requester lost two-Pigment escrow");
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "two-Pigment requester link changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(orange) == 1_600,
                "zero-acceptance requester lost two-Pigment escrow");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == 1_600
                        && fixture.requester.accepted + reconnected.requester.accepted == 1_700
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "two-Pigment requester reconnect repeated output or progress");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void samePigmentRecipeConsumesCheckedAggregateInOneDispatch(GameTestHelper helper)
            throws Exception {
        var recipe = helper.getLevel().getRecipeManager()
                .byKey(id("pack_test", "pigment_mixing_same_pigment")).orElseThrow();
        var red = key("red");
        var waterVapor = key("pink");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(red, 2), new GenericStack(red, 3)},
                new GenericStack[]{new GenericStack(waterVapor, 7)}), helper.getLevel());
        require(pattern != null && pattern.getInputs().length == 1,
                "same-Pigment CPU Pattern was not aggregated");
        var context = new PatternContext(helper.getLevel(), pattern);
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "same-Pigment recipe was not Core-verified");
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        var input = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.PIGMENT, 500);
        input.inventory().setStack(0, new GenericStack(red, 500));
        fixture.networkStorage = input.network();
        input.network().getAvailableStacks(fixture.storage.items);
        fixture.requester.limit = 100;
        var plan = fixture.calculate(waterVapor, 700);
        require(!plan.simulation(), "AE2 did not plan the same-Pigment recipe");
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && input.inventory().getAmount(0) == 0
                        && fixture.requester.accepted == 100
                        && fixture.job().bulk$outputEscrow().get(waterVapor) == 600,
                "same-Pigment one-dispatch quantities or escrow changed");
        helper.succeed();
    }

    private static MekanismKey key(String path) {
        return MekanismKey.of(MekanismAPI.pigmentRegistry().getValue(id("mekanism", path)).getStack(1));
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record TwoPigmentStorage(AEKey leftKey, MEStorage left, AEKey rightKey, MEStorage right)
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
            return Component.literal("Two bridged Pigment inputs");
        }
    }
}
