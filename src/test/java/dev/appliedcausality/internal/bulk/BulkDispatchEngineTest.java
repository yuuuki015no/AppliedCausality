package dev.appliedcausality.internal.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.ICraftingInventory;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.internal.budget.BulkDispatchReservation;
import dev.appliedcausality.internal.cost.BulkCostCommitment;
import dev.appliedcausality.internal.cost.BulkCostPolicy;
import dev.appliedcausality.internal.cost.BulkCostPreflight;
import dev.appliedcausality.internal.cost.BulkCostService;
import dev.appliedcausality.internal.cpu.BulkOutputEscrow;
import dev.appliedcausality.internal.cpu.IBulkDispatchBridge;
import dev.appliedcausality.internal.cpu.IBulkJobBridge;
import dev.appliedcausality.internal.cpu.IBulkOutputEscrow;
import dev.appliedcausality.internal.cpu.IBulkTaskView;
import dev.appliedcausality.internal.provider.IBulkCraftingProvider;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import dev.appliedcausality.test.TestProcessingPattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

class BulkDispatchEngineTest {
    private static final long ABOVE_INTEGER_MAX = (long) Integer.MAX_VALUE + 1L;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void fluidDispatchKeepsMilliBucketsExactAboveIntegerMaximum() {
        AEKey water = AEFluidKey.of(Fluids.WATER);
        AEKey lava = AEFluidKey.of(Fluids.LAVA);
        long operations = ABOVE_INTEGER_MAX;
        var fixture = fixture(verifiedModel(Map.of(water, 2L), Map.of(lava, 3L), Map.of()),
                operations, lava, Math.multiplyExact(operations, 3L));
        fixture.inventory().set(water, Math.multiplyExact(operations, 2L));
        fixture.bridge().requesterAcceptanceLimit = 7L;

        var result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMITTED, result.status());
        assertEquals(operations, result.committedMultiplier());
        assertEquals(1, result.schedulerOperations());
        assertEquals(0L, fixture.inventory().amount(water));
        assertEquals(7L, fixture.bridge().requesterReceived);
        assertEquals(Math.multiplyExact(operations, 3L) - 7L, fixture.job().escrow.get(lava));
        assertEquals(Math.multiplyExact(operations, 3L), fixture.job().completedProgress.get(lava));
        assertEquals(0L, fixture.job().bulk$getWaitingFor(lava));
        assertFalse(result.jobCompleted());
    }

    @Test
    void performanceCountersComeFromExecutedBoundaries() {
        var invocation = newBenchmarkInvocation(1_000);
        invocation.execute();
        assertEquals(
                new BenchmarkObservation(45, 1, 3, 0, 0, 0, 1, 1_000),
                invocation.verify());
    }

    @Test
    void executesRequiredNumericMatrixWithExactlyFixedMeasuredWork() {
        long[] multipliers = {
                1L,
                1_000L,
                1_000_000L,
                Integer.MAX_VALUE,
                ABOVE_INTEGER_MAX,
                Long.MAX_VALUE / 2L,
                Long.MAX_VALUE - 1L,
                Long.MAX_VALUE
        };
        Long measuredIterations = null;

        for (long multiplier : multipliers) {
            AEKey input = key(Items.IRON_INGOT);
            AEKey output = key(Items.GOLD_INGOT);
            var fixture = fixture(
                    verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                    multiplier,
                    output,
                    multiplier);
            fixture.inventory().set(input, multiplier);
            fixture.bridge().requesterAcceptanceLimit = Long.MAX_VALUE;

            BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

            assertEquals(BulkDispatchResult.Status.COMMITTED, result.status());
            assertEquals(multiplier, result.committedMultiplier());
            assertEquals(multiplier, result.finalOutputAccepted());
            assertEquals(1, result.schedulerOperations());
            assertTrue(result.jobCompleted());
            assertEquals(0L, fixture.task().remaining());
            assertEquals(0L, fixture.inventory().amount(input));
            assertEquals(multiplier, fixture.bridge().requesterReceived);
            assertTrue(fixture.job().bulk$outputEscrow().isEmpty());
            assertEquals(0L, fixture.job().bulk$getRemainingFinalAmount());

            if (measuredIterations == null) {
                measuredIterations = result.metrics().measuredIterations();
            } else {
                assertEquals(measuredIterations.longValue(), result.metrics().measuredIterations());
            }
        }
    }

    @Test
    void zeroNumericLimitPerformsNoMutation() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        fixture.inventory().set(input, 1L);
        fixture.bridge().serverConfigLimit = 0L;
        FakeState before = fixture.bridge().state();

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.NO_CAPACITY, result.status());
        assertEquals(before, fixture.bridge().state());
        assertEquals(0, result.schedulerOperations());
    }

    @Test
    void dispatchBudgetChargesOneCommittedOperationRegardlessOfMultiplier() {
        AEKey copper = key(Items.COPPER_INGOT);
        AEKey iron = key(Items.IRON_INGOT);
        AEKey gold = key(Items.GOLD_INGOT);
        long multiplier = ABOVE_INTEGER_MAX;
        var fixture = fixture(
                verifiedModel(Map.of(iron, 1L), Map.of(gold, 1L), Map.of()),
                multiplier,
                gold,
                multiplier);
        IPatternDetails dependencyPattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[0], new appeng.api.stacks.GenericStack[0]);
        var dependencyTask = new FakeTask(
                dependencyPattern, multiplier, fixture.bridge().mutationEvents);
        fixture.job().tasks.add(dependencyTask);
        fixture.bridge().providersByPattern.put(
                fixture.task().pattern(), List.of(fixture.provider()));
        fixture.bridge().providersByPattern.put(
                dependencyPattern,
                List.of(new FakeProvider(verifiedModel(Map.of(copper, 1L), Map.of(iron, 1L), Map.of()))));
        fixture.inventory().set(copper, multiplier);
        fixture.bridge().requesterAcceptanceLimit = Long.MAX_VALUE;
        fixture.bridge().dispatchBudget = new FakeDispatchBudget(1);

        BulkDispatchResult first = BulkDispatchEngine.dispatch(fixture.bridge());
        BulkDispatchResult exhausted = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMITTED, first.status());
        assertEquals(multiplier, first.committedMultiplier());
        assertEquals(1, first.schedulerOperations());
        assertEquals(1, fixture.bridge().dispatchBudget.committed);
        assertEquals(BulkDispatchResult.Status.NO_CAPACITY, exhausted.status());
        assertEquals(multiplier, fixture.task().remaining());
        assertEquals(multiplier, fixture.inventory().amount(iron));

        fixture.bridge().dispatchBudget.resetTick();
        BulkDispatchResult second = BulkDispatchEngine.dispatch(fixture.bridge());
        assertEquals(BulkDispatchResult.Status.COMMITTED, second.status());
        assertEquals(multiplier, second.committedMultiplier());
    }

    @Test
    void rejectedCommitReleasesItsDispatchReservation() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        fixture.inventory().set(input, 1L);
        fixture.inventory().shortExtractionKey = input;
        fixture.bridge().dispatchBudget = new FakeDispatchBudget(1);

        BulkDispatchResult rejected = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMIT_REJECTED, rejected.status());
        assertEquals(0, fixture.bridge().dispatchBudget.committed);
        assertEquals(0, fixture.bridge().dispatchBudget.reserved);
        assertTrue(fixture.bridge().dispatchBudget.hasCapacity());
    }

    @Test
    void gridMovementAfterReservationLeavesBulkUncommitted() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        fixture.inventory().set(input, 1L);
        fixture.bridge().dispatchBudget = new FakeDispatchBudget(1);
        fixture.bridge().dispatchBudget.reservationCurrent = false;
        FakeState before = fixture.bridge().state();

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.PREFLIGHT_CHANGED, result.status());
        assertEquals(before, fixture.bridge().state());
        assertEquals(0, fixture.bridge().dispatchBudget.committed);
        assertEquals(0, fixture.bridge().dispatchBudget.reserved);
    }

    @Test
    void exceptionDuringCommitConservativelyConsumesItsDispatchReservation() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        fixture.inventory().set(input, 1L);
        fixture.inventory().throwOnModulateExtraction = true;
        fixture.bridge().dispatchBudget = new FakeDispatchBudget(1);

        assertThrows(IllegalStateException.class, () -> BulkDispatchEngine.dispatch(fixture.bridge()));
        assertEquals(1, fixture.bridge().dispatchBudget.committed);
        assertEquals(0, fixture.bridge().dispatchBudget.reserved);
        assertFalse(fixture.bridge().dispatchBudget.hasCapacity());
    }

    @Test
    void preservesExactOwnershipThroughPartialFinalAcceptance() {
        AEKey iron = key(Items.IRON_INGOT);
        AEKey copper = key(Items.COPPER_INGOT);
        AEKey gold = key(Items.GOLD_INGOT);
        AEKey slag = key(Items.FLINT);
        AEKey bucket = key(Items.BUCKET);
        var process = verifiedModel(
                linkedAmounts(iron, 2L, copper, 1L),
                linkedAmounts(gold, 2L, slag, 1L),
                Map.of(bucket, 1L));
        var fixture = fixture(process, 5L, gold, 9L);
        fixture.inventory().set(iron, 10L);
        fixture.inventory().set(copper, 5L);
        fixture.job().waiting.put(gold, 1L);
        fixture.job().escrow.add(gold, 3L);
        fixture.job().escrow.add(slag, 2L);
        fixture.bridge().requesterAcceptanceLimit = 4L;

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMITTED, result.status());
        assertEquals(5L, result.committedMultiplier());
        assertEquals(4L, result.finalOutputAccepted());
        assertEquals(0L, fixture.inventory().amount(iron));
        assertEquals(0L, fixture.inventory().amount(copper));
        assertEquals(7L, fixture.inventory().amount(slag));
        assertEquals(5L, fixture.inventory().amount(bucket));
        assertEquals(9L, fixture.job().escrow.get(gold));
        assertEquals(0L, fixture.job().escrow.get(slag));
        assertEquals(0L, fixture.job().escrow.get(bucket));
        assertEquals(1L, fixture.job().bulk$getWaitingFor(gold));
        assertEquals(10L, fixture.job().completedProgress.get(gold));
        assertEquals(5L, fixture.job().completedProgress.get(slag));
        assertEquals(5L, fixture.job().completedProgress.get(bucket));
        assertEquals(5L, fixture.job().bulk$getRemainingFinalAmount());
        assertEquals(4L, fixture.bridge().requesterReceived);
        assertFalse(result.jobCompleted());
        assertCommitOrdering(fixture.bridge().mutationEvents);
    }

    @Test
    void zeroAcceptanceLeavesEveryFinalUnitInEscrowWithoutRepeatingProgress() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 2L), Map.of()),
                3L,
                output,
                6L);
        fixture.inventory().set(input, 3L);
        fixture.bridge().requesterAcceptanceLimit = 0L;

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMITTED, result.status());
        assertEquals(0L, result.finalOutputAccepted());
        assertEquals(6L, fixture.job().escrow.get(output));
        assertEquals(6L, fixture.job().completedProgress.get(output));
        assertEquals(0L, fixture.job().bulk$getWaitingFor(output));
        assertEquals(6L, fixture.job().bulk$getRemainingFinalAmount());
        assertFalse(result.jobCompleted());
    }

    @Test
    void completedDemandTransfersRecipeRoundingSurplusToCpuInventory() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 3L), Map.of()),
                2L,
                output,
                5L);
        fixture.inventory().set(input, 2L);
        fixture.bridge().requesterAcceptanceLimit = Long.MAX_VALUE;

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(5L, result.finalOutputAccepted());
        assertEquals(5L, fixture.bridge().requesterReceived);
        assertEquals(1L, fixture.inventory().amount(output));
        assertEquals(0L, fixture.job().bulk$getRemainingFinalAmount());
        assertTrue(fixture.job().escrow.isEmpty());
        assertTrue(result.jobCompleted());
    }

    @Test
    void standaloneJobMovesFinalOutputToCpuInventory() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 2L), Map.of()),
                4L,
                output,
                8L);
        fixture.inventory().set(input, 4L);
        fixture.job().standalone = true;

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(8L, result.finalOutputAccepted());
        assertEquals(8L, fixture.inventory().amount(output));
        assertEquals(0L, fixture.bridge().requesterReceived);
        assertTrue(fixture.job().escrow.isEmpty());
        assertTrue(result.jobCompleted());
    }

    @Test
    void finalDeliveryDoesNotFinishJobWhileAnotherTaskRemains() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        fixture.inventory().set(input, 1L);
        fixture.bridge().requesterAcceptanceLimit = 1L;
        fixture.job().tasks.add(new FakeTask(
                TestProcessingPattern.create(
                        new IPatternDetails.IInput[0],
                        new appeng.api.stacks.GenericStack[0]),
                2L,
                fixture.bridge().mutationEvents));

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertTrue(result.committed());
        assertFalse(result.jobCompleted());
        assertEquals(1, fixture.bridge().job.tasks.size());
        assertEquals(2L, fixture.bridge().job.tasks.get(0).remaining());
        assertFalse(fixture.bridge().completed);
    }

    @Test
    void inputBlockedLeadingTaskDoesNotPreventExecutableDependencyTask() {
        AEKey iron = key(Items.IRON_INGOT);
        AEKey copper = key(Items.COPPER_INGOT);
        AEKey gold = key(Items.GOLD_INGOT);
        VerifiedProcessModel finalProcess = verifiedModel(
                Map.of(iron, 1L), Map.of(gold, 1L), Map.of());
        var fixture = fixture(finalProcess, 1L, gold, 1L);

        IPatternDetails dependencyPattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[0], new appeng.api.stacks.GenericStack[0]);
        var dependencyTask = new FakeTask(
                dependencyPattern, 1L, fixture.bridge().mutationEvents);
        VerifiedProcessModel dependencyProcess = verifiedModel(
                Map.of(copper, 1L), Map.of(iron, 1L), Map.of());
        var dependencyProvider = new FakeProvider(dependencyProcess);
        fixture.job().tasks.add(dependencyTask);
        fixture.bridge().providersByPattern.put(
                fixture.task().pattern(), List.of(fixture.provider()));
        fixture.bridge().providersByPattern.put(
                dependencyPattern, List.of(dependencyProvider));
        fixture.inventory().set(copper, 1L);
        fixture.bridge().requesterAcceptanceLimit = Long.MAX_VALUE;

        BulkDispatchResult dependencyResult = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMITTED, dependencyResult.status());
        assertEquals(1L, fixture.task().remaining());
        assertEquals(0L, dependencyTask.remaining());
        assertEquals(1L, fixture.inventory().amount(iron));
        assertFalse(dependencyResult.jobCompleted());

        BulkDispatchResult finalResult = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMITTED, finalResult.status());
        assertEquals(0L, fixture.task().remaining());
        assertEquals(1L, fixture.bridge().requesterReceived);
        assertTrue(finalResult.jobCompleted());
    }

    @Test
    void bulkChainKeepsAggregatedOwnershipAboveIntegerMax() {
        AEKey copper = key(Items.COPPER_INGOT);
        AEKey iron = key(Items.IRON_INGOT);
        AEKey gold = key(Items.GOLD_INGOT);
        long multiplier = ABOVE_INTEGER_MAX;

        VerifiedProcessModel finalProcess = verifiedModel(
                Map.of(iron, 1L), Map.of(gold, 1L), Map.of());
        var fixture = fixture(finalProcess, multiplier, gold, multiplier);

        IPatternDetails dependencyPattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[0], new appeng.api.stacks.GenericStack[0]);
        var dependencyTask = new FakeTask(
                dependencyPattern, multiplier, fixture.bridge().mutationEvents);
        VerifiedProcessModel dependencyProcess = verifiedModel(
                Map.of(copper, 1L), Map.of(iron, 1L), Map.of());
        var dependencyProvider = new FakeProvider(dependencyProcess);
        fixture.job().tasks.add(dependencyTask);
        fixture.bridge().providersByPattern.put(
                fixture.task().pattern(), List.of(fixture.provider()));
        fixture.bridge().providersByPattern.put(
                dependencyPattern, List.of(dependencyProvider));
        fixture.inventory().set(copper, multiplier);
        fixture.bridge().requesterAcceptanceLimit = Long.MAX_VALUE;

        BulkDispatchResult dependencyResult = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMITTED, dependencyResult.status());
        assertEquals(multiplier, dependencyResult.committedMultiplier());
        assertEquals(1, dependencyResult.schedulerOperations());
        assertEquals(multiplier, fixture.task().remaining());
        assertEquals(0L, dependencyTask.remaining());
        assertEquals(0L, fixture.inventory().amount(copper));
        assertEquals(multiplier, fixture.inventory().amount(iron));
        assertEquals(multiplier, fixture.job().completedProgress.get(iron));
        assertEquals(0L, fixture.job().bulk$getWaitingFor(iron));
        assertTrue(fixture.job().bulk$outputEscrow().isEmpty());
        assertFalse(dependencyResult.jobCompleted());

        BulkDispatchResult finalResult = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMITTED, finalResult.status());
        assertEquals(multiplier, finalResult.committedMultiplier());
        assertEquals(1, finalResult.schedulerOperations());
        assertEquals(0L, fixture.task().remaining());
        assertEquals(0L, fixture.inventory().amount(iron));
        assertEquals(multiplier, fixture.bridge().requesterReceived);
        assertEquals(multiplier, fixture.job().completedProgress.get(gold));
        assertEquals(0L, fixture.job().bulk$getWaitingFor(gold));
        assertTrue(fixture.job().bulk$outputEscrow().isEmpty());
        assertTrue(finalResult.jobCompleted());
    }

    @Test
    void allPreflightCapacityFailuresLeaveStateUntouched() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);

        var noInput = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        assertNoCapacityMutation(noInput);

        var waitingOverflow = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        waitingOverflow.inventory().set(input, 1L);
        waitingOverflow.job().waiting.put(output, Long.MAX_VALUE);
        assertNoCapacityMutation(waitingOverflow);

        var escrowOverflow = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        escrowOverflow.inventory().set(input, 1L);
        escrowOverflow.job().escrow.add(output, Long.MAX_VALUE);
        assertNoCapacityMutation(escrowOverflow);

        var inventoryOverflow = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        inventoryOverflow.inventory().set(input, 1L);
        inventoryOverflow.inventory().set(output, Long.MAX_VALUE);
        assertNoCapacityMutation(inventoryOverflow);

        var insufficientCost = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        insufficientCost.inventory().set(input, 1L);
        FakeState beforeInsufficientCost = insufficientCost.bridge().state();

        BulkDispatchResult insufficientCostResult = BulkDispatchEngine.dispatch(
                insufficientCost.bridge(), new BulkCostService(context -> new BulkCostPreflight(
                        0L, multiplier -> BulkCostPreflight.none().commitmentFor(1L))));

        assertEquals(BulkDispatchResult.Status.NO_CAPACITY, insufficientCostResult.status());
        assertEquals(beforeInsufficientCost, insufficientCost.bridge().state());
        assertEquals(1L, insufficientCost.inventory().amount(input));
    }

    @Test
    void providerAuthorityLossImmediatelyBeforeCommitFailsWithoutMutation() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        fixture.inventory().set(input, 1L);
        fixture.provider().successfulLookups = 2;
        FakeState before = fixture.bridge().state();

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.PREFLIGHT_CHANGED, result.status());
        assertEquals(before, fixture.bridge().state());
    }

    @Test
    void shortExtractionRestoresInputsAndCostBeforeRejectingCommit() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                5L,
                output,
                5L);
        fixture.inventory().set(input, 5L);
        fixture.inventory().shortExtractionKey = input;
        var costPolicy = new FakeCostPolicy(10L, Long.MAX_VALUE, fixture.bridge().mutationEvents);
        FakeState before = fixture.bridge().state();

        BulkDispatchResult result = BulkDispatchEngine.dispatch(
                fixture.bridge(), new BulkCostService(costPolicy));

        assertEquals(BulkDispatchResult.Status.COMMIT_REJECTED, result.status());
        assertEquals(before, fixture.bridge().state());
        assertEquals(10L, costPolicy.available);
        assertEquals(0, result.schedulerOperations());
    }

    @Test
    void shortSecondInputExtractionRestoresEveryPreviouslyExtractedKey() {
        AEKey iron = key(Items.IRON_INGOT);
        AEKey copper = key(Items.COPPER_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(
                        linkedAmounts(iron, 2L, copper, 2L),
                        Map.of(output, 1L),
                        Map.of()),
                1L,
                output,
                1L);
        fixture.inventory().set(iron, 2L);
        fixture.inventory().set(copper, 2L);
        fixture.inventory().shortExtractionCall = 2;
        FakeState before = fixture.bridge().state();

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMIT_REJECTED, result.status());
        assertEquals(before, fixture.bridge().state());
        assertEquals(List.of("input", "input"), fixture.bridge().mutationEvents);
    }

    @Test
    void simulateAcceptanceDoesNotAuthorizeCommitAndZeroRetriesDoNotReaccount() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                1L,
                output,
                1L);
        fixture.inventory().set(input, 1L);
        fixture.bridge().simulatedRequesterAcceptanceLimit = 1L;
        fixture.bridge().requesterAcceptanceLimit = 0L;

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertEquals(BulkDispatchResult.Status.COMMITTED, result.status());
        assertEquals(0L, result.finalOutputAccepted());
        assertEquals(1L, fixture.job().escrow.get(output));
        assertEquals(1L, fixture.job().completedProgress.get(output));
        assertEquals(0L, fixture.job().bulk$getWaitingFor(output));

        assertEquals(0L, fixture.bridge().bulk$deliverFinalFromEscrow(output, 1L));
        assertEquals(0L, fixture.bridge().bulk$deliverFinalFromEscrow(output, 1L));
        assertEquals(1L, fixture.job().escrow.get(output));
        assertEquals(1L, fixture.job().completedProgress.get(output));
        assertEquals(0L, fixture.job().bulk$getWaitingFor(output));
        assertEquals(1L, fixture.job().bulk$getRemainingFinalAmount());
        assertEquals(0L, fixture.bridge().requesterReceived);
    }

    @Test
    void rejectedCostCommitOccursBeforeAnyInputMutation() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        var fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                2L,
                output,
                2L);
        fixture.inventory().set(input, 2L);
        var costPolicy = new FakeCostPolicy(4L, 1L, fixture.bridge().mutationEvents);
        FakeState before = fixture.bridge().state();

        BulkDispatchResult result = BulkDispatchEngine.dispatch(
                fixture.bridge(), new BulkCostService(costPolicy));

        assertEquals(BulkDispatchResult.Status.COMMIT_REJECTED, result.status());
        assertEquals(before, fixture.bridge().state());
        assertEquals(List.of("cost"), fixture.bridge().mutationEvents);
        assertEquals(4L, costPolicy.available);
    }

    @Test
    void selectionSkipsProvidersWithoutCoreVerifiedModel() {
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        VerifiedProcessModel model = verifiedModel(
                Map.of(input, 1L), Map.of(output, 1L), Map.of());
        var fixture = fixture(model, 1L, output, 1L);
        fixture.inventory().set(input, 1L);
        var unverified = new FakeProvider(null);
        fixture.bridge().providers = List.of(unverified, fixture.provider());

        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());

        assertTrue(result.committed());
        assertEquals(1, unverified.lookups);
        assertSame(model, fixture.provider().model);
        assertEquals(4L, result.metrics().providerIterations());
    }

    private static void assertNoCapacityMutation(Fixture fixture) {
        FakeState before = fixture.bridge().state();
        BulkDispatchResult result = BulkDispatchEngine.dispatch(fixture.bridge());
        assertEquals(BulkDispatchResult.Status.NO_CAPACITY, result.status());
        assertEquals(before, fixture.bridge().state());
    }

    private static void assertCommitOrdering(List<String> events) {
        int cost = events.indexOf("cost");
        int input = events.indexOf("input");
        int waiting = events.indexOf("waiting");
        int task = events.indexOf("task");
        int materialize = events.indexOf("materialize");
        int intermediate = events.indexOf("intermediate");
        int delivery = events.indexOf("final");
        int dirty = events.indexOf("dirty");
        int finish = events.indexOf("finish");
        int cleanup = events.indexOf("cleanup");

        // No optional cost is configured in this fixture, so the first observed
        // mutation is input extraction. Every remaining category must follow
        // the accepted BulkCommitPlan order.
        assertEquals(-1, cost);
        assertTrue(input >= 0);
        assertTrue(input < waiting);
        assertTrue(waiting < task);
        assertTrue(task < materialize);
        assertTrue(materialize < intermediate);
        assertTrue(intermediate < delivery);
        assertTrue(delivery < dirty);
        assertTrue(dirty < finish);
        assertTrue(finish < cleanup);
    }

    private static Fixture fixture(
            VerifiedProcessModel model,
            long taskRemaining,
            AEKey finalOutput,
            long remainingFinal) {
        IPatternDetails pattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[0], new appeng.api.stacks.GenericStack[0]);
        var events = new ArrayList<String>();
        var task = new FakeTask(pattern, taskRemaining, events);
        var job = new FakeJob(task, finalOutput, remainingFinal, events);
        var inventory = new FakeInventory(events);
        var provider = new FakeProvider(model);
        var bridge = new FakeBridge(job, inventory, provider, events);
        return new Fixture(task, job, inventory, provider, bridge);
    }

    private static Map<AEKey, Long> linkedAmounts(
            AEKey firstKey, long firstAmount, AEKey secondKey, long secondAmount) {
        var result = new LinkedHashMap<AEKey, Long>();
        result.put(firstKey, firstAmount);
        result.put(secondKey, secondAmount);
        return result;
    }

    private static VerifiedProcessModel verifiedModel(
            Map<AEKey, Long> inputs,
            Map<AEKey, Long> outputs,
            Map<AEKey, Long> remainingItems) {
        try {
            Constructor<VerifiedProcessModel> constructor = VerifiedProcessModel.class.getDeclaredConstructor(
                    ResourceLocation.class,
                    ResourceLocation.class,
                    ResourceLocation.class,
                    ProcessShapeId.class,
                    ResourceLocation.class,
                    ResourceLocation.class,
                    Map.class,
                    Map.class,
                    Map.class,
                    ProcessProperties.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    id("adapter"),
                    id("recipe"),
                    ResourceLocation.withDefaultNamespace("smelting"),
                    ProcessShapeId.of("test", "item_to_item"),
                    id("family"),
                    null,
                    inputs,
                    outputs,
                    remainingItems,
                    new ProcessProperties(true, true, true, true));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to create test-only verified model", exception);
        }
    }

    private static AEKey key(Item item) {
        return AEItemKey.of(item);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    static BenchmarkInvocation newBenchmarkInvocation(long multiplier) {
        if (multiplier <= 0) {
            throw new IllegalArgumentException("benchmark multiplier must be positive");
        }
        AEKey input = key(Items.IRON_INGOT);
        AEKey output = key(Items.GOLD_INGOT);
        Fixture fixture = fixture(
                verifiedModel(Map.of(input, 1L), Map.of(output, 1L), Map.of()),
                multiplier,
                output,
                multiplier);
        fixture.inventory().set(input, multiplier);
        fixture.bridge().requesterAcceptanceLimit = Long.MAX_VALUE;

        return new BenchmarkInvocation() {
            private BulkDispatchResult result;
            private BulkExecutionMetrics.Snapshot metrics;

            @Override
            public void execute() {
                if (result != null) {
                    throw new IllegalStateException("benchmark invocation may execute only once");
                }
                try (var recording = BulkExecutionMetrics.begin()) {
                    result = BulkDispatchEngine.dispatch(fixture.bridge());
                    metrics = recording.snapshot();
                }
            }

            @Override
            public BenchmarkObservation verify() {
                if (result == null) {
                    throw new IllegalStateException("benchmark invocation has not executed");
                }
                if (result.status() != BulkDispatchResult.Status.COMMITTED
                        || result.committedMultiplier() != multiplier
                        || result.finalOutputAccepted() != multiplier
                        || result.schedulerOperations() != 1
                        || !result.jobCompleted()
                        || fixture.task().remaining() != 0
                        || fixture.inventory().amount(input) != 0
                        || fixture.bridge().requesterReceived != multiplier
                        || !fixture.job().bulk$outputEscrow().isEmpty()
                        || fixture.job().bulk$getRemainingFinalAmount() != 0) {
                    throw new AssertionError("benchmark dispatch violated exact ownership/accounting");
                }
                if (metrics == null
                        || metrics.providerLookups() != fixture.bridge().providerLookups
                        || metrics.verifiedModelCacheLookups() != fixture.provider().lookups) {
                    throw new AssertionError("benchmark event recorder diverged from fixture observations");
                }
                return new BenchmarkObservation(
                        result.metrics().measuredIterations(),
                        metrics.providerLookups(),
                        metrics.verifiedModelCacheLookups(),
                        metrics.recipeResolutions(),
                        metrics.normalPushPatternCalls(),
                        metrics.externalCapabilityCalls(),
                        metrics.bulkDispatches(),
                        metrics.bulkOperations());
            }
        };
    }

    interface BenchmarkInvocation {
        void execute();

        BenchmarkObservation verify();
    }

    record BenchmarkObservation(
            long measuredIterations,
            long providerLookups,
            long verifiedModelCacheLookups,
            long recipeResolutions,
            long normalPushPatternCalls,
            long externalCapabilityCalls,
            long bulkDispatches,
            long bulkOperationsExecuted) {
    }

    private record Fixture(
            FakeTask task,
            FakeJob job,
            FakeInventory inventory,
            FakeProvider provider,
            FakeBridge bridge) {
    }

    private static final class FakeProvider implements IBulkCraftingProvider {
        private final VerifiedProcessModel model;
        private int successfulLookups = Integer.MAX_VALUE;
        private int lookups;

        private FakeProvider(VerifiedProcessModel model) {
            this.model = model;
        }

        @Override
        public Optional<VerifiedProcessModel> getVerifiedProcessModel(IPatternDetails pattern) {
            lookups++;
            return model != null && lookups <= successfulLookups
                    ? Optional.of(model)
                    : Optional.empty();
        }

        @Override
        public long getMaxBulkOperations() {
            return Long.MAX_VALUE;
        }
    }

    private static final class FakeTask implements IBulkTaskView {
        private final IPatternDetails pattern;
        private final List<String> events;
        private long remaining;

        private FakeTask(IPatternDetails pattern, long remaining, List<String> events) {
            this.pattern = pattern;
            this.remaining = remaining;
            this.events = events;
        }

        @Override
        public IPatternDetails pattern() {
            return pattern;
        }

        @Override
        public long remaining() {
            return remaining;
        }

        @Override
        public void decrement(long amount) {
            events.add("task");
            remaining = BulkLongMath.checkedSubtract(remaining, amount);
        }
    }

    private static final class FakeJob implements IBulkJobBridge {
        private final List<FakeTask> tasks = new ArrayList<>();
        private final Map<AEKey, Long> waiting = new HashMap<>();
        private final Map<AEKey, Long> completedProgress = new HashMap<>();
        private final BulkOutputEscrow escrow = new BulkOutputEscrow();
        private final AEKey finalOutput;
        private final List<String> events;
        private boolean standalone;
        private boolean canceled;
        private long remainingFinal;
        private FakeBridge bridge;

        private FakeJob(
                FakeTask task,
                AEKey finalOutput,
                long remainingFinal,
                List<String> events) {
            tasks.add(task);
            this.finalOutput = finalOutput;
            this.remainingFinal = remainingFinal;
            this.events = events;
        }

        @Override
        public Iterable<IBulkTaskView> bulk$tasks() {
            return List.copyOf(tasks);
        }

        @Override
        public void bulk$cleanCompletedTasks() {
            tasks.removeIf(task -> task.remaining() == 0);
        }

        @Override
        public long bulk$getWaitingFor(AEKey key) {
            return waiting.getOrDefault(key, 0L);
        }

        @Override
        public void bulk$addWaitingFor(AEKey key, long amount) {
            events.add("waiting");
            waiting.put(key, BulkLongMath.checkedAdd(bulk$getWaitingFor(key), amount));
        }

        @Override
        public void bulk$materializeWaitingToEscrow(AEKey key, long amount) {
            events.add("materialize");
            long currentWaiting = bulk$getWaitingFor(key);
            if (amount > currentWaiting) {
                throw new IllegalArgumentException("amount exceeds waiting ownership");
            }
            escrow.add(key, amount);
            completedProgress.put(
                    key,
                    BulkLongMath.checkedAdd(completedProgress.getOrDefault(key, 0L), amount));
            long remainingWaiting = BulkLongMath.checkedSubtract(currentWaiting, amount);
            if (remainingWaiting == 0) {
                waiting.remove(key);
            } else {
                waiting.put(key, remainingWaiting);
            }
        }

        @Override
        public IBulkOutputEscrow bulk$outputEscrow() {
            return escrow;
        }

        @Override
        public boolean bulk$isFinalOutput(AEKey key) {
            return finalOutput.equals(key);
        }

        @Override
        public AEKey bulk$getFinalOutputKey() {
            return finalOutput;
        }

        @Override
        public boolean bulk$isStandalone() {
            return standalone;
        }

        @Override
        public boolean bulk$isCanceled() {
            return canceled;
        }

        @Override
        public long bulk$getRemainingFinalAmount() {
            return remainingFinal;
        }

        @Override
        public void bulk$decrementRemainingFinal(long amount) {
            remainingFinal = BulkLongMath.checkedSubtract(remainingFinal, amount);
        }

        @Override
        public long bulk$simulateRequesterDelivery(AEKey key, long amount) {
            return bridge.simulateRequester(key, amount);
        }

        @Override
        public long bulk$deliverToRequester(AEKey key, long amount) {
            return bridge.deliverRequester(key, amount);
        }

        @Override
        public void bulk$readOutputEscrow(CompoundTag jobData) {
            escrow.readFromNBT(jobData);
        }

        @Override
        public void bulk$writeOutputEscrow(CompoundTag jobData) {
            CompoundTag saved = escrow.writeToNBT();
            for (String key : saved.getAllKeys()) {
                jobData.put(key, saved.get(key));
            }
        }
    }

    private static final class FakeInventory implements ICraftingInventory {
        private final Map<AEKey, Long> amounts = new HashMap<>();
        private final List<String> events;
        private AEKey shortExtractionKey;
        private int shortExtractionCall;
        private int modulateExtractionCalls;
        private boolean throwOnModulateExtraction;

        private FakeInventory(List<String> events) {
            this.events = events;
        }

        private void set(AEKey key, long amount) {
            if (amount == 0) {
                amounts.remove(key);
            } else {
                amounts.put(key, amount);
            }
        }

        private long amount(AEKey key) {
            return amounts.getOrDefault(key, 0L);
        }

        @Override
        public void insert(AEKey key, long amount, Actionable mode) {
            if (mode == Actionable.MODULATE && amount > 0) {
                amounts.put(key, BulkLongMath.checkedAdd(amount(key), amount));
            }
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode) {
            long extracted = Math.min(amount(key), amount);
            if (mode == Actionable.MODULATE) {
                modulateExtractionCalls++;
                if (throwOnModulateExtraction) {
                    throw new IllegalStateException("controlled extraction failure");
                }
                if ((key.equals(shortExtractionKey)
                        || modulateExtractionCalls == shortExtractionCall)
                        && extracted > 0) {
                    extracted--;
                }
            }
            if (mode == Actionable.MODULATE && extracted > 0) {
                events.add("input");
                set(key, BulkLongMath.checkedSubtract(amount(key), extracted));
            }
            return extracted;
        }

        @Override
        public Iterable<AEKey> findFuzzyTemplates(AEKey key) {
            return List.of();
        }
    }

    private static final class FakeBridge implements IBulkDispatchBridge {
        private final FakeJob job;
        private final FakeInventory inventory;
        private final List<String> mutationEvents;
        private List<FakeProvider> providers;
        private final Map<IPatternDetails, List<FakeProvider>> providersByPattern =
                new IdentityHashMap<>();
        private long serverConfigLimit = Long.MAX_VALUE;
        private long requesterAcceptanceLimit;
        private long simulatedRequesterAcceptanceLimit = Long.MAX_VALUE;
        private long requesterReceived;
        private int dirtyCount;
        private int providerLookups;
        private boolean completed;
        private boolean committing;
        private FakeDispatchBudget dispatchBudget = new FakeDispatchBudget(Integer.MAX_VALUE);

        private FakeBridge(
                FakeJob job,
                FakeInventory inventory,
                FakeProvider provider,
                List<String> events) {
            this.job = job;
            this.inventory = inventory;
            this.providers = List.of(provider);
            this.mutationEvents = events;
            job.bridge = this;
        }

        @Override
        public void bulk$beginCommit() {
            if (committing) {
                throw new IllegalStateException("nested fake commit");
            }
            committing = true;
        }

        @Override
        public void bulk$endCommit(boolean outcomeKnown) {
            if (!committing) {
                throw new IllegalStateException("unbalanced fake commit");
            }
            committing = false;
        }

        @Override
        public IBulkJobBridge bulk$getJob() {
            return job;
        }

        @Override
        public ICraftingInventory bulk$getInventory() {
            return inventory;
        }

        @Override
        public void bulk$markDirty() {
            mutationEvents.add("dirty");
            dirtyCount++;
        }

        @Override
        public long bulk$simulateFinalDelivery(AEKey key, long amount) {
            if (job.standalone) {
                return amount;
            }
            return simulateRequester(key, Math.min(amount, job.remainingFinal));
        }

        @Override
        public long bulk$deliverFinalFromEscrow(AEKey key, long amount) {
            mutationEvents.add("final");
            if (amount > job.escrow.get(key)) {
                throw new IllegalArgumentException("amount exceeds escrow ownership");
            }
            if (job.standalone) {
                job.escrow.remove(key, amount);
                job.bulk$decrementRemainingFinal(Math.min(amount, job.remainingFinal));
                inventory.insert(key, amount, Actionable.MODULATE);
                return amount;
            }

            long offered = Math.min(amount, job.remainingFinal);
            long accepted = deliverRequester(key, offered);
            job.escrow.remove(key, accepted);
            job.bulk$decrementRemainingFinal(accepted);
            if (job.remainingFinal == 0) {
                long surplus = job.escrow.get(key);
                job.escrow.remove(key, surplus);
                inventory.insert(key, surplus, Actionable.MODULATE);
            }
            return accepted;
        }

        @Override
        public void bulk$acceptIntermediateFromEscrow(AEKey key, long amount) {
            mutationEvents.add("intermediate");
            if (job.bulk$isFinalOutput(key)) {
                throw new IllegalArgumentException("final output used intermediate path");
            }
            job.escrow.remove(key, amount);
            inventory.insert(key, amount, Actionable.MODULATE);
        }

        @Override
        public boolean bulk$tryFinishJob() {
            mutationEvents.add("finish");
            completed = job.remainingFinal == 0 && job.escrow.isEmpty();
            return completed;
        }

        @Override
        public Iterable<? extends IBulkCraftingProvider> bulk$getProviders(IPatternDetails pattern) {
            providerLookups++;
            return providersByPattern.getOrDefault(pattern, providers);
        }

        @Override
        public boolean bulk$hasDispatchCapacity() {
            return dispatchBudget.hasCapacity();
        }

        @Override
        public BulkDispatchReservation bulk$tryReserveDispatch() {
            return dispatchBudget.tryReserve();
        }

        @Override
        public long bulk$getServerConfigLimit() {
            return serverConfigLimit;
        }

        @Override
        public void bulk$cleanCompletedTasks() {
            mutationEvents.add("cleanup");
            job.tasks.removeIf(task -> task.remaining() == 0);
        }

        private long simulateRequester(AEKey key, long amount) {
            if (!job.bulk$isFinalOutput(key)) {
                throw new IllegalArgumentException("not final output");
            }
            return Math.min(amount, simulatedRequesterAcceptanceLimit);
        }

        private long deliverRequester(AEKey key, long amount) {
            if (!job.bulk$isFinalOutput(key)) {
                throw new IllegalArgumentException("not final output");
            }
            long accepted = Math.min(amount, requesterAcceptanceLimit);
            requesterReceived = BulkLongMath.checkedAdd(requesterReceived, accepted);
            return accepted;
        }

        private FakeState state() {
            return new FakeState(
                    Map.copyOf(inventory.amounts),
                    Map.copyOf(job.waiting),
                    escrowAmounts(job.escrow),
                    Map.copyOf(job.completedProgress),
                    job.tasks.isEmpty() ? 0L : job.tasks.get(0).remaining(),
                    job.remainingFinal,
                    requesterReceived,
                    dirtyCount,
                    completed);
        }
    }

    private static final class FakeCostPolicy implements BulkCostPolicy {
        private long available;
        private final long commitLimit;
        private final List<String> mutationEvents;

        private FakeCostPolicy(long available, long commitLimit, List<String> mutationEvents) {
            this.available = available;
            this.commitLimit = commitLimit;
            this.mutationEvents = mutationEvents;
        }

        @Override
        public BulkCostPreflight preflight(dev.appliedcausality.internal.cost.BulkCostPlanningContext context) {
            long limit = Math.min(context.requestedMultiplier(), available);
            return new BulkCostPreflight(limit, multiplier -> new BulkCostCommitment() {
                private boolean consumed;

                @Override
                public boolean tryConsume() {
                    mutationEvents.add("cost");
                    if (multiplier > available || multiplier > commitLimit) {
                        return false;
                    }
                    available = BulkLongMath.checkedSubtract(available, multiplier);
                    consumed = true;
                    return true;
                }

                @Override
                public void restore() {
                    if (!consumed) {
                        throw new IllegalStateException("cost was not consumed");
                    }
                    available = BulkLongMath.checkedAdd(available, multiplier);
                    consumed = false;
                }
            });
        }
    }

    private static final class FakeDispatchBudget {
        private final int limit;
        private int committed;
        private int reserved;
        private boolean reservationCurrent = true;

        private FakeDispatchBudget(int limit) {
            this.limit = limit;
        }

        private boolean hasCapacity() {
            return committed + reserved < limit;
        }

        private BulkDispatchReservation tryReserve() {
            if (!hasCapacity()) {
                return null;
            }
            reserved++;
            return new BulkDispatchReservation() {
                private boolean resolved;

                @Override
                public boolean isCurrent() {
                    return reservationCurrent;
                }

                @Override
                public void commit() {
                    resolve();
                    committed++;
                }

                @Override
                public void release() {
                    resolve();
                }

                private void resolve() {
                    if (resolved || reserved <= 0) {
                        throw new IllegalStateException("invalid fake dispatch reservation");
                    }
                    resolved = true;
                    reserved--;
                }
            };
        }

        private void resetTick() {
            committed = 0;
            reserved = 0;
        }
    }

    private static Map<AEKey, Long> escrowAmounts(IBulkOutputEscrow escrow) {
        var result = new HashMap<AEKey, Long>();
        for (var entry : escrow.entries()) {
            result.put(entry.what(), entry.amount());
        }
        return Map.copyOf(result);
    }

    private record FakeState(
            Map<AEKey, Long> inventory,
            Map<AEKey, Long> waiting,
            Map<AEKey, Long> escrow,
            Map<AEKey, Long> completedProgress,
            long taskRemaining,
            long remainingFinal,
            long requesterReceived,
            int dirtyCount,
            boolean completed) {
    }
}
