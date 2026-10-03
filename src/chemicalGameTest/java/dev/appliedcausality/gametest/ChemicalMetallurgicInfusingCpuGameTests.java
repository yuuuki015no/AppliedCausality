package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.crafting.CraftingLink;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ProcessModelValidator;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.recipes.MetallurgicInfuserRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real Metallurgic Infusing model through stock AE2 CPU and the appmek Infusion bridge. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalMetallurgicInfusingCpuGameTests {
    private ChemicalMetallurgicInfusingCpuGameTests() {
    }

    @GameTest(template = "empty")
    public static void realTwoInputRecipeOwnsOutputAcrossRestartAndCancel(GameTestHelper helper)
            throws Exception {
        var recipeId = ResourceLocation.fromNamespaceAndPath("mekanism",
                "metallurgic_infusing/mossy/cobblestone");
        var recipe = (MetallurgicInfuserRecipe) helper.getLevel().getRecipeManager()
                .byKey(recipeId).orElseThrow();
        var item = AEItemKey.of(Items.COBBLESTONE);
        var infusion = MekanismKey.of(recipe.getChemicalInput().getRepresentations().get(0));
        var output = AEItemKey.of(Items.MOSSY_COBBLESTONE);
        require(infusion != null && infusion.getForm() == MekanismKey.INFUSION,
                "built-in Biomass Infusion key was unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(item, 1), new GenericStack(infusion, 10)},
                new GenericStack[]{new GenericStack(output, 1)}), helper.getLevel());
        require(pattern != null, "built-in two-input Pattern did not decode");
        var context = new PatternContext(helper.getLevel(), pattern);
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(new ProcessCandidate(
                MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "real two-input recipe did not produce a Core-verified model");

        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        var chemical = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.INFUSION, 1_000L);
        chemical.inventory().setStack(0, new GenericStack(infusion, 1_000L));
        fixture.storage.items.add(item, 100L);
        chemical.network().getAvailableStacks(fixture.storage.items);
        fixture.networkStorage = new MixedStorage(fixture.storage, chemical.network(), output);
        fixture.requester.limit = 10L;
        var plan = fixture.calculate(output, 100L);
        require(!plan.simulation(), "AE2 did not plan the real Item/Infusion recipe");
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && fixture.storage.items.get(item) == 0L
                        && chemical.inventory().getAmount(0) == 0L
                        && fixture.requester.accepted == 10L,
                "one Bulk dispatch did not consume exact Item and appmek Infusion inputs");
        var job = fixture.job();
        require(job.bulk$outputEscrow().get(output) == 90L
                        && job.bulk$getRemainingFinalAmount() == 90L
                        && job.bulk$getWaitingFor(output) == 0L,
                "real two-input output was not owned once by escrow");

        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(output) == 90L,
                "real two-input restart changed escrow");
        var returned = new MixedStorage(restored.storage, chemical.network(), output);
        returned.outputCapacity = 0L;
        restored.networkStorage = returned;
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(output) == 90L
                        && restored.storage.items.get(output) == 0L,
                "cancel lost output rejected by network storage");
        returned.outputCapacity = 40L;
        restored.logic.storeItems();
        require(restored.storage.items.get(output) == 40L
                        && restored.logic.getInventory().list.get(output) == 50L,
                "partial Item return changed output ownership");
        returned.outputCapacity = 90L;
        restored.logic.storeItems();
        require(restored.storage.items.get(output) == 90L
                        && restored.logic.getInventory().list.get(output) == 0L,
                "retry duplicated or lost returned Item output");

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(output) == 90L,
                "disconnected requester lost Item escrow");
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "real two-input job link identity changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0L;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(output) == 90L,
                "zero-acceptance requester lost Item escrow");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == 90L
                        && fixture.requester.accepted + reconnected.requester.accepted == 100L
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "requester reconnect duplicated output or progress");
        helper.succeed();
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
            return Component.literal("Metallurgic Infusing mixed test storage");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
