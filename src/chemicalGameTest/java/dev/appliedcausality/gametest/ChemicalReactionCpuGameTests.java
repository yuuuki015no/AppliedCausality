package dev.appliedcausality.gametest;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

/** Three-input reaction jobs own either final output across appmek and AE2. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalReactionCpuGameTests {
    private static final AEItemKey COBBLE = AEItemKey.of(Items.COBBLESTONE);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);

    private ChemicalReactionCpuGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInSubstrateInputAndOutputKeepExactOwnership(GameTestHelper helper)
            throws Exception {
        var substrate = AEItemKey.of(ForgeRegistries.ITEMS.getValue(
                ResourceLocation.fromNamespaceAndPath("mekanism", "substrate")));
        var ethene = gas("ethene");
        var oxygen = gas("oxygen");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(substrate, 1), new GenericStack(WATER, 200),
                        new GenericStack(ethene, 100)},
                new GenericStack[]{new GenericStack(oxygen, 10), new GenericStack(substrate, 8)}),
                helper.getLevel());
        require(pattern != null, "built-in reaction Pattern did not decode");
        var model = verified(helper, pattern, ResourceLocation.fromNamespaceAndPath(
                "mekanism", "reaction/substrate/water_ethene"));
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(pattern, model));
        fixture.storage.items.set(substrate, 1);
        fixture.storage.items.set(WATER, 200);
        var etheneStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 100);
        etheneStore.inventory().setStack(0, new GenericStack(ethene, 100));
        var oxygenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 0);
        etheneStore.network().getAvailableStacks(fixture.storage.items);
        fixture.networkStorage = new MixedStorage(fixture.storage,
                ethene, etheneStore.network(), oxygen, oxygenStore.network(), substrate);
        fixture.requester.limit = 5;
        var plan = fixture.calculate(oxygen, 10);
        require(!plan.simulation() && plan.patternTimes().values().stream().mapToLong(Long::longValue).sum() == 1,
                "AE2 did not plan the built-in same-Item reaction exactly: missing="
                        + "substrate=" + plan.missingItems().get(substrate)
                        + ", water=" + plan.missingItems().get(WATER)
                        + ", ethene=" + plan.missingItems().get(ethene)
                        + ", oxygen=" + plan.missingItems().get(oxygen)
                        + ", used=" + plan.usedItems().get(substrate)
                        + ", patterns=" + plan.patternTimes());
        fixture.submit(plan, fixture.requester);
        int dispatched = fixture.execute();
        require(dispatched == 1
                        && fixture.storage.items.get(WATER) == 0
                        && etheneStore.inventory().getAmount(0) == 0
                        && fixture.requester.accepted == 5
                        && fixture.job().bulk$outputEscrow().get(oxygen) == 5
                        && fixture.job().bulk$getWaitingFor(substrate) == 0
                        && fixture.job().bulk$getWaitingFor(oxygen) == 0
                        && fixture.storage.items.get(substrate)
                                + fixture.logic.getInventory().list.get(substrate) == 8,
                "built-in reaction duplicated or lost same-key input/output or Gas final: dispatch="
                        + dispatched + ", water=" + fixture.storage.items.get(WATER)
                        + ", ethene=" + etheneStore.inventory().getAmount(0)
                        + ", accepted=" + fixture.requester.accepted
                        + ", escrow=" + fixture.job().bulk$outputEscrow().get(oxygen)
                        + ", waitItem=" + fixture.job().bulk$getWaitingFor(substrate)
                        + ", waitGas=" + fixture.job().bulk$getWaitingFor(oxygen)
                        + ", itemStored=" + fixture.storage.items.get(substrate)
                        + ", itemCpu=" + fixture.logic.getInventory().list.get(substrate));
        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(oxygen) == 5,
                "same-Item reaction Gas escrow changed on save/load");
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(substrate)
                                + restored.storage.items.get(substrate) == 8
                        && restored.logic.getInventory().list.get(oxygen)
                                + restored.storage.items.get(oxygen) == 5,
                "same-Item reaction cancel duplicated or lost Item/Gas output");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void itemFinalAndGasByproductSurviveRestartAndCancel(GameTestHelper helper)
            throws Exception {
        var hydrogen = gas("hydrogen");
        var oxygen = gas("oxygen");
        var pattern = pattern(helper, false, hydrogen, oxygen);
        var model = verified(helper, pattern);
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(pattern, model));
        fixture.storage.items.set(COBBLE, 200);
        fixture.storage.items.set(WATER, 700);
        var hydrogenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 1_100);
        hydrogenStore.inventory().setStack(0, new GenericStack(hydrogen, 1_100));
        var oxygenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 0);
        hydrogenStore.network().getAvailableStacks(fixture.storage.items);
        fixture.networkStorage = new MixedStorage(fixture.storage,
                hydrogen, hydrogenStore.network(), oxygen, oxygenStore.network(), IRON);
        fixture.requester.limit = 100;
        var plan = fixture.calculate(IRON, 1_300);
        require(!plan.simulation(), "AE2 did not plan dual-output reaction: missing="
                + plan.missingItems() + ", used=" + plan.usedItems()
                + ", patterns=" + plan.patternTimes());
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && fixture.storage.items.get(COBBLE) == 0
                        && fixture.storage.items.get(WATER) == 0
                        && hydrogenStore.inventory().getAmount(0) == 0
                        && fixture.requester.accepted == 100,
                "reaction dispatch did not consume three inputs exactly once");
        var job = fixture.job();
        require(job.bulk$outputEscrow().get(IRON) == 1_200
                        && job.bulk$getRemainingFinalAmount() == 1_200
                        && job.bulk$getWaitingFor(IRON) == 0
                        && job.bulk$getWaitingFor(oxygen) == 0
                        && fixture.logic.getInventory().list.get(oxygen) == 1_700,
                "reaction Item final or Gas byproduct lost ownership");

        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        var returned = new MixedStorage(restored.storage,
                hydrogen, hydrogenStore.network(), oxygen, oxygenStore.network(), IRON);
        returned.itemCapacity = 0;
        restored.networkStorage = returned;
        require(restored.job().bulk$outputEscrow().get(IRON) == 1_200,
                "reaction Item escrow changed on save/load");
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(IRON) == 1_200
                        && restored.logic.getInventory().list.get(oxygen) == 1_700,
                "reaction cancel lost rejected Item or Gas output");
        returned.itemCapacity = 500;
        oxygenStore.inventory().setCapacity(MekanismKeyType.TYPE, 1_700);
        restored.logic.storeItems();
        require(restored.storage.items.get(IRON) == 500
                        && restored.logic.getInventory().list.get(IRON) == 700
                        && oxygenStore.inventory().getAmount(0) == 1_700
                        && restored.logic.getInventory().list.get(oxygen) == 0,
                "reaction partial Item/Gas return changed output ownership");
        returned.itemCapacity = 1_200;
        restored.logic.storeItems();
        require(restored.storage.items.get(IRON) == 1_200
                        && restored.logic.getInventory().list.get(IRON) == 0,
                "reaction retry duplicated or lost Item output");

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(IRON) == 1_200,
                "disconnected reaction requester lost Item escrow");
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "reaction requester identity changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(IRON) == 1_200,
                "zero acceptance lost reaction Item escrow");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == 1_200
                        && fixture.requester.accepted + reconnected.requester.accepted == 1_300
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && reconnected.logic.getInventory().list.get(oxygen)
                                + reconnected.storage.items.get(oxygen) == 1_700
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "reaction requester reconnect repeated Item, Gas, or progress");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void gasFinalAndItemByproductOwnAllOutput(GameTestHelper helper) throws Exception {
        var hydrogen = gas("hydrogen");
        var oxygen = gas("oxygen");
        var pattern = pattern(helper, true, hydrogen, oxygen);
        var model = verified(helper, pattern);
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(pattern, model));
        fixture.storage.items.set(COBBLE, 200);
        fixture.storage.items.set(WATER, 700);
        var hydrogenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 1_100);
        hydrogenStore.inventory().setStack(0, new GenericStack(hydrogen, 1_100));
        var oxygenStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 0);
        hydrogenStore.network().getAvailableStacks(fixture.storage.items);
        fixture.networkStorage = new MixedStorage(fixture.storage,
                hydrogen, hydrogenStore.network(), oxygen, oxygenStore.network(), IRON);
        fixture.requester.limit = 100;
        var plan = fixture.calculate(oxygen, 1_700);
        require(!plan.simulation(), "AE2 did not plan Gas-final reaction: missing="
                + plan.missingItems() + ", used=" + plan.usedItems()
                + ", patterns=" + plan.patternTimes());
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && fixture.storage.items.get(COBBLE) == 0
                        && fixture.storage.items.get(WATER) == 0
                        && hydrogenStore.inventory().getAmount(0) == 0
                        && fixture.requester.accepted == 100
                        && fixture.job().bulk$outputEscrow().get(oxygen) == 1_600
                        && fixture.job().bulk$getWaitingFor(oxygen) == 0
                        && fixture.job().bulk$getWaitingFor(IRON) == 0
                        && fixture.logic.getInventory().list.get(IRON) == 1_300,
                "reaction Gas final or Item byproduct lost ownership");
        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        restored.networkStorage = new MixedStorage(restored.storage,
                hydrogen, hydrogenStore.network(), oxygen, oxygenStore.network(), IRON);
        require(restored.job().bulk$outputEscrow().get(oxygen) == 1_600,
                "reaction Gas escrow changed on save/load");
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(oxygen) == 1_600
                        && restored.logic.getInventory().list.get(IRON)
                                + restored.storage.items.get(IRON) == 1_300,
                "reaction cancel lost Gas final or Item byproduct");
        oxygenStore.inventory().setCapacity(MekanismKeyType.TYPE, 600);
        restored.logic.storeItems();
        require(oxygenStore.inventory().getAmount(0) == 600
                        && restored.logic.getInventory().list.get(oxygen) == 1_000
                        && restored.storage.items.get(IRON) == 1_300,
                "reaction partial Gas return changed ownership");
        oxygenStore.inventory().setCapacity(MekanismKeyType.TYPE, 1_600);
        restored.logic.storeItems();
        require(oxygenStore.inventory().getAmount(0) == 1_600
                        && restored.logic.getInventory().list.get(oxygen) == 0,
                "reaction Gas retry duplicated or lost output");

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(oxygen) == 1_600,
                "disconnected reaction requester lost Gas escrow");
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "reaction Gas requester identity changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(oxygen) == 1_600,
                "zero acceptance lost reaction Gas escrow");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == 1_600
                        && fixture.requester.accepted + reconnected.requester.accepted == 1_700
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && reconnected.logic.getInventory().list.get(IRON)
                                + reconnected.storage.items.get(IRON) == 1_300
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "reaction Gas requester reconnect repeated Gas, Item, or progress");
        helper.succeed();
    }

    private static appeng.api.crafting.IPatternDetails pattern(GameTestHelper helper,
            boolean gasFirst, MekanismKey hydrogen, MekanismKey oxygen) {
        GenericStack item = new GenericStack(IRON, 13);
        GenericStack gas = new GenericStack(oxygen, 17);
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(COBBLE, 2), new GenericStack(WATER, 7),
                        new GenericStack(hydrogen, 11)},
                gasFirst ? new GenericStack[]{gas, item} : new GenericStack[]{item, gas}),
                helper.getLevel());
        require(pattern != null, "reaction Pattern did not decode");
        return pattern;
    }

    private static dev.appliedcausality.internal.validation.VerifiedProcessModel verified(
            GameTestHelper helper, appeng.api.crafting.IPatternDetails pattern) {
        return verified(helper, pattern, ResourceLocation.fromNamespaceAndPath("pack_test", "reaction"));
    }

    private static dev.appliedcausality.internal.validation.VerifiedProcessModel verified(
            GameTestHelper helper, appeng.api.crafting.IPatternDetails pattern,
            ResourceLocation recipeId) {
        var context = new PatternContext(helper.getLevel(), pattern);
        var recipe = helper.getLevel().getRecipeManager()
                .byKey(recipeId)
                .orElseThrow();
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(new ProcessCandidate(
                MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "real reaction recipe was not Core-verified");
        return validation.model().orElseThrow();
    }

    private static MekanismKey gas(String path) {
        return MekanismKey.of(MekanismAPI.gasRegistry().getValue(
                ResourceLocation.fromNamespaceAndPath("mekanism", path)).getStack(1));
    }

    private static final class MixedStorage implements MEStorage {
        private final Phase11StandardAe2GameTests.TestStorage items;
        private final AEKey inputGas;
        private final MEStorage inputStore;
        private final AEKey outputGas;
        private final MEStorage outputStore;
        private final AEItemKey outputItem;
        private long itemCapacity = Long.MAX_VALUE;

        private MixedStorage(Phase11StandardAe2GameTests.TestStorage items, AEKey inputGas,
                MEStorage inputStore, AEKey outputGas, MEStorage outputStore, AEItemKey outputItem) {
            this.items = items;
            this.inputGas = inputGas;
            this.inputStore = inputStore;
            this.outputGas = outputGas;
            this.outputStore = outputStore;
            this.outputItem = outputItem;
        }

        private MEStorage forKey(AEKey key) {
            return key.equals(inputGas) ? inputStore : key.equals(outputGas) ? outputStore : items;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            return forKey(key).extract(key, amount, mode, source);
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (key.equals(outputItem)) {
                long accepted = Math.min(amount, Math.max(0, itemCapacity - items.items.get(outputItem)));
                return items.insert(key, accepted, mode, source);
            }
            return forKey(key).insert(key, amount, mode, source);
        }

        @Override
        public Component getDescription() {
            return Component.literal("Reaction Item/Fluid and two appmek Gas stores");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
