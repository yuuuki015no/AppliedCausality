package dev.appliedcausality.gametest;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.internal.provider.IBulkCraftingProvider;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Test-only verified-model injection exercises real AE2 planning and CPU delivery without enabling a Fluid recipe Type. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FluidFoundationGameTests {
    private FluidFoundationGameTests() {
    }

    @GameTest(template = "empty")
    public static void realAe2PlanningAndBulkFluidOwnership(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        AEKey input = AEFluidKey.of(Fluids.WATER);
        AEKey output = AEFluidKey.of(Fluids.LAVA);
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, 2_000L)},
                new GenericStack[]{new GenericStack(output, 3_000L)}), helper.getLevel());
        require(pattern != null, "test Fluid pattern failed to decode");
        f.register(new FluidTestProvider(pattern, verifiedFluidModel(input, output)));
        f.storage.items.set(input, 20_000L);
        f.requester.limit = 700L;
        var plan = f.calculate(output, 30_000L);
        require(!plan.simulation(), "AE2 did not plan the Fluid recipe");
        f.submit(plan, f.requester);

        require(f.execute() == 1, "Fluid Bulk dispatch did not use one scheduler operation");
        var job = f.job();
        require(f.storage.items.get(input) == 0L && f.requester.accepted == 700L,
                "Fluid input or requester acceptance changed mB amount");
        require(job.bulk$outputEscrow().get(output) == 29_300L
                        && job.bulk$getRemainingFinalAmount() == 29_300L
                        && job.bulk$getWaitingFor(output) == 0L,
                "Fluid output ownership is incorrect after partial acceptance");
        var saved = new CompoundTag();
        f.logic.writeToNBT(saved);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(output) == 29_300L,
                "live AE2 restart lost Fluid escrow");
        restored.storage.acceptInsert = false;
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(output) == 29_300L,
                "live AE2 cancel did not retain exact Fluid remainder");
        helper.succeed();
    }

    private static VerifiedProcessModel verifiedFluidModel(AEKey input, AEKey output) throws Exception {
        // The production constructor is deliberately private. This fixture bypasses recipe discovery only.
        Constructor<VerifiedProcessModel> constructor = VerifiedProcessModel.class.getDeclaredConstructor(
                ResourceLocation.class, ResourceLocation.class, ResourceLocation.class,
                ProcessShapeId.class, ResourceLocation.class, ResourceLocation.class,
                Map.class, Map.class, Map.class, ProcessProperties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                id("fluid_test_adapter"), id("fluid_test_recipe"), id("fluid_test_type"),
                ProcessShapeId.of("applied_causality", "fluid_to_fluid"), id("fluid_test_family"), null,
                Map.of(input, 2_000L), Map.of(output, 3_000L), Map.of(),
                new ProcessProperties(true, true, true, true));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("applied_causality", path);
    }

    private record FluidTestProvider(IPatternDetails pattern, VerifiedProcessModel model)
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
            throw new AssertionError("verified Fluid process fell back to external machine path");
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
