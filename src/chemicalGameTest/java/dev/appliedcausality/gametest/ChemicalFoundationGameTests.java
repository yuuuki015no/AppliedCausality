package dev.appliedcausality.gametest;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.helpers.externalstorage.GenericStackInv;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.internal.cpu.BulkOutputEscrow;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ProcessModelValidator;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.internal.provider.IBulkCraftingProvider;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import dev.appliedcausality.recipe.mekanism.InfusionConversionResolverTestAccess;
import dev.appliedcausality.recipe.mekanism.OxidizingResolverTestAccess;
import me.ramidzkh.mekae2.ae2.GenericStackChemicalStorage;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import me.ramidzkh.mekae2.ae2.MekanismKeyType;
import me.ramidzkh.mekae2.ae2.stack.ChemicalHandlerFacade;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.slurry.SlurryStack;
import mekanism.api.recipes.chemical.ItemStackToChemicalRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Optional-addon foundation tests; included only with -PchemicalFoundationGameTest. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalFoundationGameTests {
    private static final long PATTERN_AMOUNT = (long) Integer.MAX_VALUE + 1L;
    private static final long ESCROW_AMOUNT = PATTERN_AMOUNT + 17L;

    private ChemicalFoundationGameTests() {
    }

    @GameTest(template = "empty")
    public static void gasPatternAndEscrow(GameTestHelper helper) {
        var gas = MekanismAPI.gasRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_GAS).findFirst().orElseThrow();
        verify(helper, new GasStack(gas, 7L), MekanismKey.GAS);
    }

    @GameTest(template = "empty")
    public static void infusionPatternAndEscrow(GameTestHelper helper) {
        var infusion = MekanismAPI.infuseTypeRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_INFUSE_TYPE).findFirst().orElseThrow();
        verify(helper, new InfusionStack(infusion, 7L), MekanismKey.INFUSION);
    }

    @GameTest(template = "empty")
    public static void pigmentPatternAndEscrow(GameTestHelper helper) {
        var pigment = MekanismAPI.pigmentRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_PIGMENT).findFirst().orElseThrow();
        verify(helper, new PigmentStack(pigment, 7L), MekanismKey.PIGMENT);
    }

    @GameTest(template = "empty")
    public static void slurryPatternAndEscrow(GameTestHelper helper) {
        var slurry = MekanismAPI.slurryRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_SLURRY).findFirst().orElseThrow();
        verify(helper, new SlurryStack(slurry, 7L), MekanismKey.SLURRY);
    }

    @GameTest(template = "empty")
    public static void gasCpuOwnership(GameTestHelper helper) throws Exception {
        var types = MekanismAPI.gasRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_GAS).limit(2).toList();
        require(types.size() == 2, "Gas registry lacks two test types");
        verifyCpuOwnership(helper, new GasStack(types.get(0), 7L), new GasStack(types.get(1), 7L));
    }

    @GameTest(template = "empty")
    public static void infusionCpuOwnership(GameTestHelper helper) throws Exception {
        var types = MekanismAPI.infuseTypeRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_INFUSE_TYPE).limit(2).toList();
        require(types.size() == 2, "Infusion registry lacks two test types");
        verifyCpuOwnership(helper, new InfusionStack(types.get(0), 7L), new InfusionStack(types.get(1), 7L));
    }

    @GameTest(template = "empty")
    public static void pigmentCpuOwnership(GameTestHelper helper) throws Exception {
        var types = MekanismAPI.pigmentRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_PIGMENT).limit(2).toList();
        require(types.size() == 2, "Pigment registry lacks two test types");
        verifyCpuOwnership(helper, new PigmentStack(types.get(0), 7L), new PigmentStack(types.get(1), 7L));
    }

    @GameTest(template = "empty")
    public static void slurryCpuOwnership(GameTestHelper helper) throws Exception {
        var types = MekanismAPI.slurryRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_SLURRY).limit(2).toList();
        require(types.size() == 2, "Slurry registry lacks two test types");
        verifyCpuOwnership(helper, new SlurryStack(types.get(0), 7L), new SlurryStack(types.get(1), 7L));
    }

    @GameTest(template = "empty")
    public static void realOxidizingRecipeOwnsGasAcrossCpuCancel(GameTestHelper helper) throws Exception {
        verifyRealRecipeOwnership(helper, "oxidizing");
    }

    @GameTest(template = "empty")
    public static void realInfusionConversionOwnsInfusionAcrossCpuCancel(GameTestHelper helper) throws Exception {
        verifyRealRecipeOwnership(helper, "infusion_conversion");
    }

    @GameTest(template = "empty")
    public static void realGasConversionOwnsGasAcrossCpuCancel(GameTestHelper helper) throws Exception {
        verifyRealRecipeOwnership(helper, "gas_conversion");
    }

    @GameTest(template = "empty")
    public static void realPigmentExtractingOwnsPigmentAcrossCpuCancel(GameTestHelper helper) throws Exception {
        verifyRealRecipeOwnership(helper, "pigment_extracting");
    }

    private static void verifyRealRecipeOwnership(GameTestHelper helper, String recipePath)
            throws Exception {
        var recipeId = ResourceLocation.fromNamespaceAndPath("pack_test", recipePath);
        var recipe = helper.getLevel().getRecipeManager().byKey(recipeId).orElseThrow();
        var input = AEItemKey.of(Items.SPORE_BLOSSOM);
        var output = MekanismKey.of(((ItemStackToChemicalRecipe<?, ?>) recipe)
                .getOutput(new ItemStack(Items.SPORE_BLOSSOM)));
        int expectedForm = switch (recipePath) {
            case "infusion_conversion" -> MekanismKey.INFUSION;
            case "pigment_extracting" -> MekanismKey.PIGMENT;
            case "oxidizing", "gas_conversion" -> MekanismKey.GAS;
            default -> throw new AssertionError("unknown recipe path: " + recipePath);
        };
        require(input != null && output != null && output.getForm() == expectedForm,
                "real recipe Chemical keys were unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, 2L)},
                new GenericStack[]{new GenericStack(output, 17L)}), helper.getLevel());
        require(pattern != null, "real Chemical recipe Pattern did not decode");
        var context = new PatternContext(helper.getLevel(), pattern);
        var candidate = (switch (recipePath) {
            case "infusion_conversion" -> InfusionConversionResolverTestAccess.resolve(recipe, context);
            case "oxidizing" -> OxidizingResolverTestAccess.resolve(recipe, context);
            case "gas_conversion" -> MekanismRecipeAdapter.createBundled().resolve(recipe, context);
            case "pigment_extracting" -> MekanismRecipeAdapter.createBundled().resolve(recipe, context);
            default -> throw new AssertionError("unknown recipe path: " + recipePath);
        }).orElseThrow();
        var validation = ProcessModelValidator.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "real Chemical recipe did not produce a Core-verified model");

        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalTestProvider(pattern, validation.model().orElseThrow()));
        fixture.storage.items.add(input, 200L);
        fixture.requester.limit = 100L;
        var plan = fixture.calculate(output, 1_700L);
        require(!plan.simulation(), "AE2 did not plan the real Chemical recipe model");
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && fixture.storage.items.get(input) == 0L
                        && fixture.requester.accepted == 100L,
                "real Chemical CPU dispatch changed exact input or accepted output");
        var job = fixture.job();
        require(job.bulk$outputEscrow().get(output) == 1_600L
                        && job.bulk$getRemainingFinalAmount() == 1_600L
                        && job.bulk$getWaitingFor(output) == 0L,
                "real Chemical output was not owned once by escrow");

        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(output) == 1_600L,
                "real Chemical escrow changed on save/load");
        var returned = bridgedStorage(output.getForm(), 0L);
        restored.networkStorage = returned.network();
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(output) == 1_600L
                        && returned.inventory().getAmount(0) == 0L,
                "cancel did not retain rejected Chemical in CPU inventory");
        returned.inventory().setCapacity(MekanismKeyType.TYPE, 600L);
        restored.logic.storeItems();
        require(returned.inventory().getAmount(0) == 600L
                        && restored.logic.getInventory().list.get(output) == 1_000L,
                "partial appmek return changed real Chemical ownership");
        returned.inventory().setCapacity(MekanismKeyType.TYPE, 1_700L);
        restored.logic.storeItems();
        require(returned.inventory().getAmount(0) == 1_600L
                        && restored.logic.getInventory().list.get(output) == 0L,
                "real Chemical return retry lost or duplicated output");

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(output) == 1_600L,
                "disconnected real-recipe requester lost Chemical escrow");
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "real-recipe requester link identity changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0L;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(output) == 1_600L,
                "reconnected zero-acceptance requester lost Chemical escrow");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == 1_600L
                        && fixture.requester.accepted + reconnected.requester.accepted == 1_700L
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "real-recipe requester reconnect duplicated Chemical or progress");
        helper.succeed();
    }

    private static void verify(GameTestHelper helper, ChemicalStack<?> stack, byte expectedForm) {
        var key = MekanismKey.of(stack);
        require(key != null && key.getForm() == expectedForm, "Chemical key form was not preserved");

        var encoded = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(key, PATTERN_AMOUNT)},
                new GenericStack[]{new GenericStack(key, PATTERN_AMOUNT)});
        var pattern = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        require(pattern != null, "Chemical Processing Pattern did not decode");
        var inputs = pattern.getInputs();
        var outputs = pattern.getOutputs();
        require(inputs.length == 1 && outputs.length == 1, "Chemical Pattern shape changed");
        require(inputs[0].getMultiplier() == PATTERN_AMOUNT
                        && inputs[0].getPossibleInputs().length == 1
                        && inputs[0].getPossibleInputs()[0].what().equals(key)
                        && outputs[0].what().equals(key)
                        && outputs[0].amount() == PATTERN_AMOUNT,
                "Chemical Pattern long amount changed");
        require(((MekanismKey) inputs[0].getPossibleInputs()[0].what()).getStack().getAmount() == 7L,
                "Chemical key stack amount unexpectedly became the Pattern amount");

        var escrow = new BulkOutputEscrow();
        escrow.add(key, ESCROW_AMOUNT);
        var restored = new BulkOutputEscrow();
        restored.readFromNBT(escrow.writeToNBT());
        require(restored.get(key) == ESCROW_AMOUNT && restored.distinctKeyCount() == 1,
                "Chemical escrow long amount changed on save/load");
        helper.succeed();
    }

    private static void verifyCpuOwnership(GameTestHelper helper, ChemicalStack<?> inputStack,
            ChemicalStack<?> outputStack) throws Exception {
        var input = MekanismKey.of(inputStack);
        var output = MekanismKey.of(outputStack);
        require(input != null && output != null && !input.equals(output), "Chemical CPU keys are not distinct");

        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, 2_000L)},
                new GenericStack[]{new GenericStack(output, 3_000L)}), helper.getLevel());
        require(pattern != null, "Chemical CPU Pattern did not decode");
        fixture.register(new ChemicalTestProvider(pattern, verifiedChemicalModel(input, output)));
        var inputStorage = bridgedStorage(input.getForm(), 20_000L);
        inputStorage.inventory().setStack(0, new GenericStack(input, 20_000L));
        fixture.networkStorage = inputStorage.network();
        // AE2's planning cache is a separate snapshot; execution uses the real appmek bridge above.
        inputStorage.network().getAvailableStacks(fixture.storage.items);
        require(fixture.storage.items.get(input) == 20_000L,
                "appmek bridge reported a different planning quantity");
        fixture.requester.limit = 700L;
        var plan = fixture.calculate(output, 30_000L);
        require(!plan.simulation(), "AE2 did not plan the Chemical Pattern");
        fixture.submit(plan, fixture.requester);

        require(fixture.execute() == 1, "Chemical Bulk dispatch used more than one scheduler operation");
        var job = fixture.job();
        require(inputStorage.inventory().getAmount(0) == 0L && fixture.requester.accepted == 700L,
                "Chemical CPU input/requester amount changed");
        require(job.bulk$outputEscrow().get(output) == 29_300L
                        && job.bulk$getRemainingFinalAmount() == 29_300L
                        && job.bulk$getWaitingFor(output) == 0L,
                "Chemical CPU partial acceptance did not retain exact escrow");

        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(output) == 29_300L,
                "Chemical CPU restart lost escrow");
        var returnStorage = bridgedStorage(output.getForm(), 0L);
        restored.networkStorage = returnStorage.network();
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(output) == 29_300L,
                "Chemical CPU cancel did not retain output rejected by appmek bridge");
        require(returnStorage.inventory().getAmount(0) == 0L,
                "rejected Chemical output reached appmek storage");
        returnStorage.inventory().setCapacity(MekanismKeyType.TYPE, 10_000L);
        restored.logic.storeItems();
        require(returnStorage.inventory().getAmount(0) == 10_000L
                        && restored.logic.getInventory().list.get(output) == 19_300L,
                "partial appmek return changed output ownership");
        restored.logic.storeItems();
        require(returnStorage.inventory().getAmount(0) == 10_000L
                        && restored.logic.getInventory().list.get(output) == 19_300L,
                "rejected appmek return duplicated output");
        returnStorage.inventory().setCapacity(MekanismKeyType.TYPE, 30_000L);
        restored.logic.storeItems();
        require(returnStorage.inventory().getAmount(0) == 29_300L
                        && restored.logic.getInventory().list.get(output) == 0L,
                "appmek return retry lost Chemical output");
        var returned = new KeyCounter();
        returnStorage.network().getAvailableStacks(returned);
        require(returned.get(output) == 29_300L,
                "appmek bridge reported a different returned quantity");

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(output) == 29_300L,
                "disconnected Chemical requester lost escrow");
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "Chemical job link identity changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0L;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(output) == 29_300L,
                "reconnected zero-acceptance Chemical requester lost escrow");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == 29_300L
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && reconnected.logic.getInventory().list.get(output) == 0L
                        && reconnected.storage.items.get(output) == 0L,
                "reconnected Chemical requester did not receive exact remainder");
        require(completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "Chemical retry repeated progress accounting");
        int deliveries = reconnected.requester.deliveries;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.deliveries == deliveries
                        && fixture.requester.accepted + reconnected.requester.accepted == 30_000L,
                "Chemical restart duplicated or lost requester output");
        helper.succeed();
    }

    static BridgedStorage bridgedStorage(byte form, long capacity) {
        var inventory = new GenericStackInv(() -> { }, 1);
        inventory.setCapacity(MekanismKeyType.TYPE, capacity);
        IChemicalHandler<?, ?> handler = switch (form) {
            case MekanismKey.GAS -> new GenericStackChemicalStorage.OfGas(inventory);
            case MekanismKey.INFUSION -> new GenericStackChemicalStorage.OfInfusion(inventory);
            case MekanismKey.PIGMENT -> new GenericStackChemicalStorage.OfPigment(inventory);
            case MekanismKey.SLURRY -> new GenericStackChemicalStorage.OfSlurry(inventory);
            default -> throw new AssertionError("unknown Chemical form " + form);
        };
        var handlers = new IChemicalHandler[4];
        handlers[form] = handler;
        return new BridgedStorage(inventory, new ChemicalHandlerFacade(handlers, false, () -> { }));
    }

    record BridgedStorage(GenericStackInv inventory, ChemicalHandlerFacade network) {
    }

    private static VerifiedProcessModel verifiedChemicalModel(AEKey input, AEKey output) throws Exception {
        // Test-only model injection bypasses recipe discovery, not AE2 planning or Bulk delivery.
        Constructor<VerifiedProcessModel> constructor = VerifiedProcessModel.class.getDeclaredConstructor(
                ResourceLocation.class, ResourceLocation.class, ResourceLocation.class,
                ProcessShapeId.class, ResourceLocation.class, ResourceLocation.class,
                Map.class, Map.class, Map.class, ProcessProperties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                id("chemical_test_adapter"), id("chemical_test_recipe"), id("chemical_test_type"),
                ProcessShapeId.of("applied_causality", "chemical_to_chemical"), id("chemical_test_family"), null,
                Map.of(input, 2_000L), Map.of(output, 3_000L), Map.of(),
                new ProcessProperties(true, true, true, true));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AppliedCausality.MOD_ID, path);
    }

    record ChemicalTestProvider(IPatternDetails pattern, VerifiedProcessModel model)
            implements ICraftingProvider, IBulkCraftingProvider {
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
            throw new AssertionError("verified Chemical process fell back to external machine path");
        }

        @Override
        public Optional<VerifiedProcessModel> getVerifiedProcessModel(IPatternDetails details) {
            return pattern.equals(details) ? Optional.of(model) : Optional.empty();
        }

        @Override
        public long getMaxBulkOperations() {
            return Long.MAX_VALUE;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
