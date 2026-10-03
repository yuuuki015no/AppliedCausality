package dev.appliedcausality.internal.bulk;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import appeng.api.stacks.AEKey;
import dev.appliedcausality.internal.cost.BulkCostCommitment;

/**
 * Immutable, side-effect-free description of one prospective Bulk commit.
 *
 * <p>The plan contains only exact execution quantities. In particular, a final
 * delivery records the maximum progress that may be made, but the later commit
 * must still use the requester's actual accepted amount as its accounting
 * truth.</p>
 */
public final class BulkCommitPlan {
    private static final List<CommitStage> COMMIT_ORDER = List.of(
            CommitStage.CONSUME_COST,
            CommitStage.EXTRACT_INPUTS,
            CommitStage.REGISTER_WAITING,
            CommitStage.DECREMENT_TASK,
            CommitStage.MATERIALIZE_WAITING_TO_ESCROW,
            CommitStage.TRANSFER_INTERMEDIATE_OUTPUTS,
            CommitStage.DELIVER_FINAL_OUTPUT,
            CommitStage.MARK_CPU_DIRTY,
            CommitStage.ALLOW_JOB_COMPLETION,
            CommitStage.CLEAN_COMPLETED_TASKS);

    private final MultiplierLimits limits;
    private final long multiplier;
    private final long taskDecrement;
    private final BulkCostCommitment costCommitment;
    private final Map<AEKey, Long> inputsToExtract;
    private final Map<AEKey, Long> waitingToRegister;
    private final Map<AEKey, Long> outputsToMaterialize;
    private final Map<AEKey, Long> intermediateOutputsToTransfer;
    private final Map<AEKey, Long> cpuInventoryReservations;
    private final Optional<FinalDelivery> finalDelivery;

    BulkCommitPlan(
            MultiplierLimits limits,
            long multiplier,
            BulkCostCommitment costCommitment,
            Map<AEKey, Long> inputsToExtract,
            Map<AEKey, Long> waitingToRegister,
            Map<AEKey, Long> outputsToMaterialize,
            Map<AEKey, Long> intermediateOutputsToTransfer,
            Map<AEKey, Long> cpuInventoryReservations,
            Optional<FinalDelivery> finalDelivery) {
        this.limits = Objects.requireNonNull(limits, "limits");
        requireNonNegative(multiplier, "multiplier");
        if (multiplier != limits.minimum()) {
            throw new IllegalArgumentException("multiplier must equal the minimum explicit limit");
        }
        this.multiplier = multiplier;
        this.taskDecrement = multiplier;
        this.costCommitment = Objects.requireNonNull(costCommitment, "costCommitment");
        this.inputsToExtract = immutablePositiveAmounts(inputsToExtract, "inputsToExtract");
        this.waitingToRegister = immutablePositiveAmounts(waitingToRegister, "waitingToRegister");
        this.outputsToMaterialize = immutablePositiveAmounts(outputsToMaterialize, "outputsToMaterialize");
        this.intermediateOutputsToTransfer = immutablePositiveAmounts(
                intermediateOutputsToTransfer, "intermediateOutputsToTransfer");
        this.cpuInventoryReservations = immutablePositiveAmounts(
                cpuInventoryReservations, "cpuInventoryReservations");
        this.finalDelivery = Objects.requireNonNull(finalDelivery, "finalDelivery");

        if (multiplier == 0 && (!this.inputsToExtract.isEmpty()
                || !this.waitingToRegister.isEmpty()
                || !this.outputsToMaterialize.isEmpty()
                || !this.intermediateOutputsToTransfer.isEmpty()
                || !this.cpuInventoryReservations.isEmpty()
                || finalDelivery.isPresent())) {
            throw new IllegalArgumentException("a zero-multiplier plan must not contain commit work");
        }
        if (!this.waitingToRegister.equals(this.outputsToMaterialize)) {
            throw new IllegalArgumentException("waiting and materialization quantities must match");
        }
        validateOutputDestinations();
    }

    /**
     * The fixed mutation order that a later engine must follow for non-empty plans.
     */
    public List<CommitStage> commitOrder() {
        return COMMIT_ORDER;
    }

    public MultiplierLimits limits() {
        return limits;
    }

    public long multiplier() {
        return multiplier;
    }

    public long taskDecrement() {
        return taskDecrement;
    }

    BulkCostCommitment costCommitment() {
        return costCommitment;
    }

    public Map<AEKey, Long> inputsToExtract() {
        return inputsToExtract;
    }

    public Map<AEKey, Long> waitingToRegister() {
        return waitingToRegister;
    }

    public Map<AEKey, Long> outputsToMaterialize() {
        return outputsToMaterialize;
    }

    /**
     * Total escrow ownership to move through the fail-free intermediate path.
     * This includes matching escrow that existed before this plan.
     */
    public Map<AEKey, Long> intermediateOutputsToTransfer() {
        return intermediateOutputsToTransfer;
    }

    /**
     * Per-key capacity that must remain available in CPU inventory for all
     * escrow ownership after materialization. This guarantees fail-free transfer
     * even if the requester accepts nothing and the job is then canceled.
     * Existing CPU inventory is not included in these reservation quantities.
     */
    public Map<AEKey, Long> cpuInventoryReservations() {
        return cpuInventoryReservations;
    }

    public Optional<FinalDelivery> finalDelivery() {
        return finalDelivery;
    }

    public boolean isEmpty() {
        return multiplier == 0;
    }

    private void validateOutputDestinations() {
        var destinationKeys = new HashSet<>(intermediateOutputsToTransfer.keySet());
        if (!outputsToMaterialize.keySet().containsAll(destinationKeys)) {
            throw new IllegalArgumentException("intermediate transfer key was not materialized by this plan");
        }
        for (var entry : intermediateOutputsToTransfer.entrySet()) {
            if (!Objects.equals(cpuInventoryReservations.get(entry.getKey()), entry.getValue())) {
                throw new IllegalArgumentException("intermediate transfer must reserve its full CPU amount");
            }
        }

        if (finalDelivery.isPresent()) {
            FinalDelivery delivery = finalDelivery.orElseThrow();
            if (!outputsToMaterialize.containsKey(delivery.key()) || !destinationKeys.add(delivery.key())) {
                throw new IllegalArgumentException("final delivery must be one distinct materialized output key");
            }
            if (!Objects.equals(cpuInventoryReservations.get(delivery.key()), delivery.escrowAmount())) {
                throw new IllegalArgumentException("final delivery must reserve its full cancel-transfer amount");
            }
        }

        if (!destinationKeys.equals(outputsToMaterialize.keySet())) {
            throw new IllegalArgumentException("every materialized output needs exactly one destination class");
        }
        if (!destinationKeys.containsAll(cpuInventoryReservations.keySet())) {
            throw new IllegalArgumentException("CPU reservation key was not materialized by this plan");
        }
    }

    private static Map<AEKey, Long> immutablePositiveAmounts(
            Map<AEKey, Long> amounts, String name) {
        Objects.requireNonNull(amounts, name);
        var copy = new LinkedHashMap<AEKey, Long>(amounts.size());
        for (var entry : amounts.entrySet()) {
            AEKey key = Objects.requireNonNull(entry.getKey(), name + " key");
            Long amount = Objects.requireNonNull(entry.getValue(), name + " amount");
            if (amount <= 0) {
                throw new IllegalArgumentException(name + " amounts must be positive");
            }
            copy.put(key, amount);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    /**
     * Explicit operation limits used to select the plan multiplier.
     */
    public record MultiplierLimits(
            long taskRemaining,
            long providerLimit,
            long serverConfigLimit,
            long inputAvailabilityLimit,
            long outputNumericLimit,
            long cpuInventoryNumericLimit,
            long waitingForNumericLimit,
            long costPreflightLimit) {
        public MultiplierLimits {
            requireNonNegative(taskRemaining, "taskRemaining");
            requireNonNegative(providerLimit, "providerLimit");
            requireNonNegative(serverConfigLimit, "serverConfigLimit");
            requireNonNegative(inputAvailabilityLimit, "inputAvailabilityLimit");
            requireNonNegative(outputNumericLimit, "outputNumericLimit");
            requireNonNegative(cpuInventoryNumericLimit, "cpuInventoryNumericLimit");
            requireNonNegative(waitingForNumericLimit, "waitingForNumericLimit");
            requireNonNegative(costPreflightLimit, "costPreflightLimit");
        }

        public long minimum() {
            long result = Math.min(taskRemaining, providerLimit);
            result = Math.min(result, serverConfigLimit);
            result = Math.min(result, inputAvailabilityLimit);
            result = Math.min(result, outputNumericLimit);
            result = Math.min(result, cpuInventoryNumericLimit);
            result = Math.min(result, waitingForNumericLimit);
            return Math.min(result, costPreflightLimit);
        }
    }

    /**
     * Exact final-key escrow offer. The eventual remaining decrement is the
     * actual accepted amount and must not exceed {@code remainingProgressLimit}.
     */
    public record FinalDelivery(
            AEKey key,
            long escrowAmount,
            long remainingProgressLimit,
            boolean standalone) {
        public FinalDelivery {
            Objects.requireNonNull(key, "key");
            if (escrowAmount <= 0) {
                throw new IllegalArgumentException("escrowAmount must be positive");
            }
            requireNonNegative(remainingProgressLimit, "remainingProgressLimit");
            if (remainingProgressLimit > escrowAmount) {
                throw new IllegalArgumentException("remainingProgressLimit must not exceed escrowAmount");
            }
        }

        public long cpuInventoryTransferIfProgressCompletes() {
            return standalone
                    ? escrowAmount
                    : BulkLongMath.checkedSubtract(escrowAmount, remainingProgressLimit);
        }
    }

    public enum CommitStage {
        CONSUME_COST,
        EXTRACT_INPUTS,
        REGISTER_WAITING,
        DECREMENT_TASK,
        /**
         * Add the planned output to escrow and consume the same waiting/progress
         * accounting exactly once. Delivery retries must not repeat this stage.
         */
        MATERIALIZE_WAITING_TO_ESCROW,
        TRANSFER_INTERMEDIATE_OUTPUTS,
        DELIVER_FINAL_OUTPUT,
        MARK_CPU_DIRTY,
        ALLOW_JOB_COMPLETION,
        CLEAN_COMPLETED_TASKS
    }
}
