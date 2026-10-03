package dev.appliedcausality.gametest;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.security.IActionSource;
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
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Owns four real-recipe Chemical inputs and Item outputs through AE2 CPU. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalCrystallizingCpuGameTests {
    private ChemicalCrystallizingCpuGameTests() {
    }

    @GameTest(template = "empty")
    public static void eachFormOwnsRealRecipeOutputAcrossCancelAndRestart(GameTestHelper helper)
            throws Exception {
        verify(helper, "crystallizing_gas",
                MekanismAPI.gasRegistry().getValue(id("mekanism", "hydrogen")).getStack(2),
                AEItemKey.of(Items.DIAMOND), 2, 3);
        verify(helper, "crystallizing_infusion",
                MekanismAPI.infuseTypeRegistry().getValue(id("mekanism", "carbon")).getStack(11),
                AEItemKey.of(Items.EMERALD), 11, 4);
        verify(helper, "crystallizing_pigment",
                MekanismAPI.pigmentRegistry().getValue(id("mekanism", "red")).getStack(13),
                AEItemKey.of(Items.LAPIS_LAZULI), 13, 5);
        verify(helper, "crystallizing_slurry",
                MekanismAPI.slurryRegistry().getValue(id("mekanism", "clean_iron")).getStack(17),
                AEItemKey.of(Items.QUARTZ), 17, 6);
        helper.succeed();
    }

    private static void verify(GameTestHelper helper, String path, ChemicalStack<?> stack,
            AEItemKey output, long inputPerOperation, long outputPerOperation) throws Exception {
        var recipeId = id("pack_test", path);
        var recipe = helper.getLevel().getRecipeManager().byKey(recipeId).orElseThrow();
        var input = MekanismKey.of(stack);
        require(input != null && output != null, "crystallizing CPU key is unavailable: " + path);
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, inputPerOperation)},
                new GenericStack[]{new GenericStack(output, outputPerOperation)}), helper.getLevel());
        require(pattern != null, "crystallizing CPU Pattern did not decode: " + path);
        var context = new PatternContext(helper.getLevel(), pattern);
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(new ProcessCandidate(
                MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "real crystallizing recipe did not produce a Core model: " + path);

        long totalInput = Math.multiplyExact(inputPerOperation, 100L);
        long totalOutput = Math.multiplyExact(outputPerOperation, 100L);
        long escrow = totalOutput - 10L;
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        var chemical = ChemicalFoundationGameTests.bridgedStorage(input.getForm(), totalInput);
        chemical.inventory().setStack(0, new GenericStack(input, totalInput));
        chemical.network().getAvailableStacks(fixture.storage.items);
        require(fixture.storage.items.get(input) == totalInput,
                "appmek bridge changed crystallizing planning input: " + path);
        fixture.networkStorage = new MixedStorage(fixture.storage, chemical.network(), output);
        fixture.requester.limit = 10L;
        var plan = fixture.calculate(output, totalOutput);
        require(!plan.simulation(), "AE2 did not plan real crystallizing recipe: " + path);
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && chemical.inventory().getAmount(0) == 0L
                        && fixture.requester.accepted == 10L,
                "one Bulk dispatch changed Chemical input or Item output: " + path);
        var job = fixture.job();
        require(job.bulk$outputEscrow().get(output) == escrow
                        && job.bulk$getRemainingFinalAmount() == escrow
                        && job.bulk$getWaitingFor(output) == 0L,
                "crystallizing Item output was not owned once by escrow: " + path);

        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(output) == escrow,
                "crystallizing escrow changed on save/load: " + path);
        var returned = new MixedStorage(restored.storage, chemical.network(), output);
        returned.outputCapacity = 0L;
        restored.networkStorage = returned;
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(output) == escrow
                        && restored.storage.items.get(output) == 0L,
                "cancel lost rejected crystallizing Item output: " + path);
        long firstReturn = escrow / 2L;
        returned.outputCapacity = firstReturn;
        restored.logic.storeItems();
        require(restored.storage.items.get(output) == firstReturn
                        && restored.logic.getInventory().list.get(output) == escrow - firstReturn,
                "partial crystallizing Item return changed ownership: " + path);
        returned.outputCapacity = escrow;
        restored.logic.storeItems();
        require(restored.storage.items.get(output) == escrow
                        && restored.logic.getInventory().list.get(output) == 0L,
                "crystallizing Item retry lost or duplicated output: " + path);

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(output) == escrow,
                "disconnected crystallizing requester lost Item escrow: " + path);
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "crystallizing requester identity changed on restart: " + path);
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0L;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(output) == escrow,
                "zero-acceptance requester lost crystallizing escrow: " + path);
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == escrow
                        && fixture.requester.accepted + reconnected.requester.accepted == totalOutput
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "crystallizing requester reconnect duplicated Item or progress: " + path);
    }

    private static final class MixedStorage implements MEStorage {
        private final Phase11StandardAe2GameTests.TestStorage items;
        private final MEStorage chemical;
        private final AEItemKey output;
        private long outputCapacity = Long.MAX_VALUE;

        private MixedStorage(Phase11StandardAe2GameTests.TestStorage items,
                MEStorage chemical, AEItemKey output) {
            this.items = items;
            this.chemical = chemical;
            this.output = output;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            return key instanceof MekanismKey ? chemical.extract(key, amount, mode, source)
                    : items.extract(key, amount, mode, source);
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (key instanceof MekanismKey) {
                return chemical.insert(key, amount, mode, source);
            }
            long accepted = key.equals(output)
                    ? Math.min(amount, Math.max(0L, outputCapacity - items.items.get(output))) : amount;
            return items.insert(key, accepted, mode, source);
        }

        @Override
        public Component getDescription() {
            return Component.literal("Crystallizing mixed test storage");
        }
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
