package dev.appliedcausality.internal.bulk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import appeng.api.stacks.AEKey;
import dev.appliedcausality.internal.cost.BulkCostPreflight;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;

/**
 * Pure multiplier and commit-quantity planning for verified processes.
 *
 * <p>Runtime integrations must snapshot their mutable state before calling this
 * class. Work is proportional only to the number of distinct process keys and
 * never to the selected multiplier.</p>
 */
public final class MultiplierPlanner {
    private MultiplierPlanner() {
    }

    public static BulkCommitPlan plan(VerifiedProcessModel process, PlanningState state) {
        return plan(process, state, BulkCostPreflight.none(), null);
    }

    public static BulkCommitPlan plan(
            VerifiedProcessModel process,
            PlanningState state,
            BulkCostPreflight costPreflight) {
        return plan(process, state, costPreflight, null);
    }

    static BulkCommitPlan plan(
            VerifiedProcessModel process,
            PlanningState state,
            BulkCostPreflight costPreflight,
            BulkWorkCounter work) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(costPreflight, "costPreflight");

        validatePositiveAmounts(process.inputsPerOperation(), "process inputs", true, work);
        validatePositiveAmounts(process.outputsPerOperation(), "process outputs", true, work);
        validatePositiveAmounts(
                process.remainingItemsPerOperation(), "process remaining items", false, work);

        OutputAmounts outputAmounts = aggregateOutputsPerOperation(process, work);
        long inputLimit = inputAvailabilityLimit(
                process.inputsPerOperation(), state.inputAvailability(), work);
        long outputLimit = outputAmounts.overflowed()
                ? 0
                : outputNumericLimit(outputAmounts.amounts(), state.escrowAmounts(), work);
        long waitingLimit = outputAmounts.overflowed()
                ? 0
                : accumulationLimit(outputAmounts.amounts(), state.waitingForAmounts(), work);
        long cpuInventoryLimit = outputAmounts.overflowed()
                ? 0
                : cpuInventoryLimit(outputAmounts.amounts(), state, work);
        var limits = new BulkCommitPlan.MultiplierLimits(
                state.taskRemaining(),
                state.providerLimit(),
                state.serverConfigLimit(),
                inputLimit,
                outputLimit,
                cpuInventoryLimit,
                waitingLimit,
                costPreflight.multiplierLimit());
        long multiplier = limits.minimum();
        if (multiplier == 0) {
            return emptyPlan(limits);
        }

        Map<AEKey, Long> inputsToExtract = multiplyAmounts(
                process.inputsPerOperation(), multiplier, work);
        Map<AEKey, Long> outputsToMaterialize = multiplyAmounts(
                outputAmounts.amounts(), multiplier, work);
        Map<AEKey, Long> intermediateTransfers = new LinkedHashMap<>();
        Map<AEKey, Long> inventoryReservations = new LinkedHashMap<>();
        BulkCommitPlan.FinalDelivery finalDelivery = null;

        for (var entry : outputsToMaterialize.entrySet()) {
            recordPlannerKey(work);
            AEKey key = entry.getKey();
            long escrowAfterMaterialization = BulkLongMath.checkedAdd(
                    state.amountIn(state.escrowAmounts(), key), entry.getValue());
            boolean finalOutput = key.equals(state.finalOutputKey());
            if (!finalOutput) {
                intermediateTransfers.put(key, escrowAfterMaterialization);
                inventoryReservations.put(key, escrowAfterMaterialization);
                continue;
            }

            long progressLimit = Math.min(escrowAfterMaterialization, state.remainingFinalAmount());
            finalDelivery = new BulkCommitPlan.FinalDelivery(
                    key, escrowAfterMaterialization, progressLimit, state.standaloneJob());
            inventoryReservations.put(key, escrowAfterMaterialization);
        }

        return new BulkCommitPlan(
                limits,
                multiplier,
                costPreflight.commitmentFor(multiplier),
                inputsToExtract,
                outputsToMaterialize,
                outputsToMaterialize,
                intermediateTransfers,
                inventoryReservations,
                Optional.ofNullable(finalDelivery));
    }

    private static BulkCommitPlan emptyPlan(BulkCommitPlan.MultiplierLimits limits) {
        return new BulkCommitPlan(
                limits,
                0,
                BulkCostPreflight.none().commitmentFor(1),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    private static OutputAmounts aggregateOutputsPerOperation(
            VerifiedProcessModel process, BulkWorkCounter work) {
        var result = new LinkedHashMap<AEKey, Long>();
        if (!addAmounts(result, process.outputsPerOperation(), work)
                || !addAmounts(result, process.remainingItemsPerOperation(), work)) {
            return new OutputAmounts(Map.of(), true);
        }
        return new OutputAmounts(Collections.unmodifiableMap(result), false);
    }

    private static boolean addAmounts(
            Map<AEKey, Long> target,
            Map<AEKey, Long> additions,
            BulkWorkCounter work) {
        for (var entry : additions.entrySet()) {
            recordPlannerKey(work);
            long current = target.getOrDefault(entry.getKey(), 0L);
            long added = entry.getValue();
            if (current > Long.MAX_VALUE - added) {
                return false;
            }
            target.put(entry.getKey(), current + added);
        }
        return true;
    }

    private static long inputAvailabilityLimit(
            Map<AEKey, Long> inputsPerOperation,
            Map<AEKey, Long> availableInputs,
            BulkWorkCounter work) {
        long result = Long.MAX_VALUE;
        for (var entry : inputsPerOperation.entrySet()) {
            recordPlannerKey(work);
            result = Math.min(result, BulkLongMath.maxMultiplierForProduct(
                    entry.getValue(), availableInputs.getOrDefault(entry.getKey(), 0L)));
        }
        return result;
    }

    private static long outputNumericLimit(
            Map<AEKey, Long> outputsPerOperation,
            Map<AEKey, Long> currentEscrow,
            BulkWorkCounter work) {
        long result = Long.MAX_VALUE;
        for (var entry : outputsPerOperation.entrySet()) {
            recordPlannerKey(work);
            long perOperation = entry.getValue();
            result = Math.min(result,
                    BulkLongMath.maxMultiplierForProduct(perOperation, Long.MAX_VALUE));
            result = Math.min(result, BulkLongMath.maxMultiplierForAccumulation(
                    currentEscrow.getOrDefault(entry.getKey(), 0L),
                    perOperation,
                    Long.MAX_VALUE));
        }
        return result;
    }

    private static long accumulationLimit(
            Map<AEKey, Long> amountsPerOperation,
            Map<AEKey, Long> currentAmounts,
            BulkWorkCounter work) {
        long result = Long.MAX_VALUE;
        for (var entry : amountsPerOperation.entrySet()) {
            recordPlannerKey(work);
            result = Math.min(result, BulkLongMath.maxMultiplierForAccumulation(
                    currentAmounts.getOrDefault(entry.getKey(), 0L),
                    entry.getValue(),
                    Long.MAX_VALUE));
        }
        return result;
    }

    private static long cpuInventoryLimit(
            Map<AEKey, Long> outputsPerOperation,
            PlanningState state,
            BulkWorkCounter work) {
        long result = Long.MAX_VALUE;
        for (var entry : outputsPerOperation.entrySet()) {
            recordPlannerKey(work);
            AEKey key = entry.getKey();
            long currentInventory = state.amountIn(state.cpuInventoryAmounts(), key);
            long currentEscrow = state.amountIn(state.escrowAmounts(), key);

            // Every escrowed unit must fit back into the fail-free CPU inventory
            // if the job is canceled before the requester accepts it. Remaining
            // demand therefore cannot increase this numeric allowance.
            if (currentEscrow > Long.MAX_VALUE - currentInventory) {
                return 0;
            }
            long maximumNewOutput = Long.MAX_VALUE - currentInventory - currentEscrow;

            result = Math.min(result, BulkLongMath.maxMultiplierForProduct(
                    entry.getValue(), maximumNewOutput));
        }
        return result;
    }

    private static Map<AEKey, Long> multiplyAmounts(
            Map<AEKey, Long> amountsPerOperation,
            long multiplier,
            BulkWorkCounter work) {
        var result = new LinkedHashMap<AEKey, Long>(amountsPerOperation.size());
        for (var entry : amountsPerOperation.entrySet()) {
            recordPlannerKey(work);
            long amount = BulkLongMath.checkedMultiply(entry.getValue(), multiplier);
            if (amount > 0) {
                result.put(entry.getKey(), amount);
            }
        }
        return result;
    }

    private static void validatePositiveAmounts(
            Map<AEKey, Long> amounts,
            String name,
            boolean required,
            BulkWorkCounter work) {
        Objects.requireNonNull(amounts, name);
        if (required && amounts.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        for (var entry : amounts.entrySet()) {
            recordPlannerKey(work);
            Objects.requireNonNull(entry.getKey(), name + " key");
            Long amount = Objects.requireNonNull(entry.getValue(), name + " amount");
            if (amount <= 0) {
                throw new IllegalArgumentException(name + " amounts must be positive");
            }
        }
    }

    private record OutputAmounts(Map<AEKey, Long> amounts, boolean overflowed) {
    }

    /**
     * Immutable snapshot of all mutable quantities consulted during planning.
     * Missing map keys mean zero; zero-valued entries are normalized away.
     */
    public record PlanningState(
            long taskRemaining,
            long providerLimit,
            long serverConfigLimit,
            Map<AEKey, Long> inputAvailability,
            Map<AEKey, Long> waitingForAmounts,
            Map<AEKey, Long> escrowAmounts,
            Map<AEKey, Long> cpuInventoryAmounts,
            AEKey finalOutputKey,
            boolean standaloneJob,
            long remainingFinalAmount) {
        public PlanningState {
            requireNonNegative(taskRemaining, "taskRemaining");
            requireNonNegative(providerLimit, "providerLimit");
            requireNonNegative(serverConfigLimit, "serverConfigLimit");
            inputAvailability = immutableNonNegativeAmounts(inputAvailability, "inputAvailability");
            waitingForAmounts = immutableNonNegativeAmounts(waitingForAmounts, "waitingForAmounts");
            escrowAmounts = immutableNonNegativeAmounts(escrowAmounts, "escrowAmounts");
            cpuInventoryAmounts = immutableNonNegativeAmounts(
                    cpuInventoryAmounts, "cpuInventoryAmounts");
            Objects.requireNonNull(finalOutputKey, "finalOutputKey");
            requireNonNegative(remainingFinalAmount, "remainingFinalAmount");
        }

        private static Map<AEKey, Long> immutableNonNegativeAmounts(
                Map<AEKey, Long> amounts, String name) {
            Objects.requireNonNull(amounts, name);
            var copy = new LinkedHashMap<AEKey, Long>(amounts.size());
            for (var entry : amounts.entrySet()) {
                AEKey key = Objects.requireNonNull(entry.getKey(), name + " key");
                Long amount = Objects.requireNonNull(entry.getValue(), name + " amount");
                requireNonNegative(amount, name + " amount");
                if (amount > 0) {
                    copy.put(key, amount);
                }
            }
            return Collections.unmodifiableMap(copy);
        }

        private long amountIn(Map<AEKey, Long> amounts, AEKey key) {
            return amounts.getOrDefault(key, 0L);
        }
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static void recordPlannerKey(BulkWorkCounter work) {
        if (work != null) {
            work.plannerKey();
        }
    }
}
