package dev.appliedcausality.internal.bulk;

import dev.appliedcausality.AppliedCausality;
import appeng.api.stacks.AEKey;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.slurry.SlurryStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Runs the common in-memory CPU with registered optional Chemical keys. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalBulkDispatchGameTests {
    private ChemicalBulkDispatchGameTests() {
    }

    @GameTest(template = "empty")
    public static void allFourFormsDispatchAboveIntegerMaximum(GameTestHelper helper) {
        var gases = MekanismAPI.gasRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_GAS).limit(2).toList();
        var infusions = MekanismAPI.infuseTypeRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_INFUSE_TYPE).limit(2).toList();
        var pigments = MekanismAPI.pigmentRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_PIGMENT).limit(2).toList();
        var slurries = MekanismAPI.slurryRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_SLURRY).limit(2).toList();
        require(gases.size() == 2 && infusions.size() == 2
                && pigments.size() == 2 && slurries.size() == 2,
                "Mekanism registry lacks distinct Chemical keys");

        verify(helper, new GasStack(gases.get(0), 7L), new GasStack(gases.get(1), 7L));
        verify(helper, new InfusionStack(infusions.get(0), 7L), new InfusionStack(infusions.get(1), 7L));
        verify(helper, new PigmentStack(pigments.get(0), 7L), new PigmentStack(pigments.get(1), 7L));
        verify(helper, new SlurryStack(slurries.get(0), 7L), new SlurryStack(slurries.get(1), 7L));
        helper.succeed();
    }

    private static void verify(GameTestHelper helper, ChemicalStack<?> inputStack,
            ChemicalStack<?> outputStack) {
        var input = MekanismKey.of(inputStack);
        var output = MekanismKey.of(outputStack);
        require(input != null && output != null && !input.equals(output),
                "Chemical fake dispatch keys are not distinct");
        long largeMultiplier = (long) Integer.MAX_VALUE + 1L;
        var small = execute(helper, input, output, 100L);
        var large = execute(helper, input, output, largeMultiplier);
        require(small.result().metrics().measuredIterations()
                        == large.result().metrics().measuredIterations(),
                "Chemical fake dispatch work grew with N");
    }

    private static DispatchObservation execute(GameTestHelper helper, AEKey input,
            AEKey output, long multiplier) {
        var fixture = new ChemicalFakeCpuFixture(helper.getLevel(), input, output, multiplier);
        BulkDispatchResult result;
        BulkExecutionMetrics.Snapshot metrics;
        try (var recording = BulkExecutionMetrics.begin()) {
            result = BulkDispatchEngine.dispatch(fixture);
            metrics = recording.snapshot();
        }
        long outputAmount = Math.multiplyExact(multiplier, 3L);
        require(result.status() == BulkDispatchResult.Status.COMMITTED
                        && result.committedMultiplier() == multiplier
                        && result.schedulerOperations() == 1
                        && !result.jobCompleted()
                        && fixture.taskRemaining() == 0L
                        && fixture.inputRemaining() == 0L
                        && fixture.requesterReceived() == 7L
                        && fixture.escrowAmount() == outputAmount - 7L
                        && fixture.remainingFinal() == outputAmount - 7L
                        && fixture.completedProgress() == outputAmount
                        && fixture.waitingAmount() == 0L,
                "Chemical fake dispatch changed exact ownership/accounting");
        require(metrics.bulkDispatches() == 1L
                        && metrics.bulkOperations() == multiplier
                        && metrics.recipeResolutions() == 0L
                        && metrics.normalPushPatternCalls() == 0L
                        && metrics.externalCapabilityCalls() == 0L,
                "Chemical fake dispatch crossed a forbidden boundary");
        return new DispatchObservation(result);
    }

    private record DispatchObservation(BulkDispatchResult result) {
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
