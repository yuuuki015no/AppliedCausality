package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.GenericStack;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Owns real-recipe Gas input and output through AE2 CPU, escrow, and the appmek bridge. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalCentrifugingCpuGameTests {
    private static final ResourceLocation RECIPE_ID = id("pack_test", "centrifuging");

    private ChemicalCentrifugingCpuGameTests() {
    }

    @GameTest(template = "empty")
    public static void realRecipeOwnsBothGasKeysAcrossCancelAndRestart(GameTestHelper helper) throws Exception {
        var recipe = helper.getLevel().getRecipeManager().byKey(RECIPE_ID).orElseThrow();
        var input = MekanismKey.of(MekanismAPI.gasRegistry().getValue(id("mekanism", "hydrogen")).getStack(2));
        var output = MekanismKey.of(MekanismAPI.gasRegistry().getValue(id("mekanism", "oxygen")).getStack(17));
        require(input != null && output != null && input.getForm() == MekanismKey.GAS
                        && output.getForm() == MekanismKey.GAS && !input.equals(output),
                "centrifuging input or output Gas key is unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, 2)},
                new GenericStack[]{new GenericStack(output, 17)}), helper.getLevel());
        require(pattern != null, "centrifuging CPU Pattern did not decode");
        var context = new PatternContext(helper.getLevel(), pattern);
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "real centrifuging recipe did not produce a Core-verified model");

        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        var inputStorage = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 200);
        inputStorage.inventory().setStack(0, new GenericStack(input, 200));
        fixture.networkStorage = inputStorage.network();
        inputStorage.network().getAvailableStacks(fixture.storage.items);
        require(fixture.storage.items.get(input) == 200, "appmek bridge changed planning input quantity");
        fixture.requester.limit = 100;
        var plan = fixture.calculate(output, 1_700);
        require(!plan.simulation(), "AE2 did not plan the real centrifuging recipe");
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && inputStorage.inventory().getAmount(0) == 0
                        && fixture.requester.accepted == 100,
                "one CPU Bulk dispatch changed exact Gas input or accepted output");
        var job = fixture.job();
        require(job.bulk$outputEscrow().get(output) == 1_600
                        && job.bulk$getRemainingFinalAmount() == 1_600
                        && job.bulk$getWaitingFor(output) == 0,
                "centrifuging output was not owned once by per-job escrow");

        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(output) == 1_600,
                "centrifuging escrow changed on save/load");
        var returned = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.GAS, 0);
        restored.networkStorage = returned.network();
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(output) == 1_600
                        && returned.inventory().getAmount(0) == 0,
                "cancel did not retain rejected centrifuging Gas in CPU inventory");
        returned.inventory().setCapacity(MekanismKeyType.TYPE, 600);
        restored.logic.storeItems();
        require(returned.inventory().getAmount(0) == 600
                        && restored.logic.getInventory().list.get(output) == 1_000,
                "partial appmek Gas return changed centrifuging ownership");
        returned.inventory().setCapacity(MekanismKeyType.TYPE, 1_700);
        restored.logic.storeItems();
        require(returned.inventory().getAmount(0) == 1_600
                        && restored.logic.getInventory().list.get(output) == 0,
                "centrifuging return retry lost or duplicated Gas");

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(output) == 1_600,
                "disconnected centrifuging requester lost Gas escrow");
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "centrifuging requester link identity changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(output) == 1_600,
                "zero-acceptance requester lost centrifuging Gas escrow");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == 1_600
                        && fixture.requester.accepted + reconnected.requester.accepted == 1_700
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "centrifuging reconnect duplicated Gas or completed-work accounting");
        helper.succeed();
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
