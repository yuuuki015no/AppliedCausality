package dev.appliedcausality.internal.bulk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.ICraftingInventory;
import dev.appliedcausality.internal.budget.BulkDispatchReservation;
import dev.appliedcausality.internal.cost.BulkCostPreflight;
import dev.appliedcausality.internal.cost.BulkCostService;
import dev.appliedcausality.internal.cpu.IBulkDispatchBridge;
import dev.appliedcausality.internal.cpu.IBulkJobBridge;
import dev.appliedcausality.internal.cpu.IBulkTaskView;
import dev.appliedcausality.internal.provider.IBulkCraftingProvider;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;

/**
 * Common transaction engine proven against fake/in-memory bridges before any
 * production CPU execution hook is enabled.
 *
 * <p>All loops in this class and its planner are bounded by task, provider, or
 * distinct-key counts. No loop is bounded by the selected multiplier.</p>
 */
public final class BulkDispatchEngine {
    private BulkDispatchEngine() {
    }

    public static BulkDispatchResult dispatch(IBulkDispatchBridge bridge) {
        return dispatch(bridge, BulkCostService.v0_1None());
    }

    static BulkDispatchResult dispatch(IBulkDispatchBridge bridge, BulkCostService costService) {
        Objects.requireNonNull(bridge, "bridge");
        Objects.requireNonNull(costService, "costService");
        var work = new BulkWorkCounter();

        IBulkJobBridge job = bridge.bulk$getJob();
        if (job == null) {
            return uncommitted(BulkDispatchResult.Status.NO_JOB, work);
        }
        if (job.bulk$isCanceled()) {
            return uncommitted(BulkDispatchResult.Status.JOB_CANCELED, work);
        }
        if (!bridge.bulk$hasDispatchCapacity()) {
            return uncommitted(BulkDispatchResult.Status.NO_CAPACITY, work);
        }

        ICraftingInventory inventory = Objects.requireNonNull(
                bridge.bulk$getInventory(), "bridge inventory");
        boolean foundVerifiedProcess = false;
        Iterable<IBulkTaskView> tasks = Objects.requireNonNull(job.bulk$tasks(), "job tasks");
        for (IBulkTaskView task : tasks) {
            work.task();
            Objects.requireNonNull(task, "task");
            long remaining = task.remaining();
            if (remaining < 0) {
                throw new IllegalStateException("task remaining must be non-negative");
            }
            if (remaining == 0) {
                continue;
            }

            IPatternDetails pattern = Objects.requireNonNull(task.pattern(), "task pattern");
            BulkExecutionMetrics.providerLookup();
            Iterable<? extends IBulkCraftingProvider> providers = Objects.requireNonNull(
                    bridge.bulk$getProviders(pattern), "providers");
            for (IBulkCraftingProvider provider : providers) {
                work.provider();
                Objects.requireNonNull(provider, "provider");
                BulkExecutionMetrics.verifiedModelCacheLookup();
                var model = Objects.requireNonNull(
                        provider.getVerifiedProcessModel(pattern), "verified model result");
                if (model.isEmpty()) {
                    continue;
                }
                foundVerifiedProcess = true;

                var selection = new Selection(task, pattern, provider, model.orElseThrow());
                PreflightSnapshot initial = snapshot(selection, job, bridge, inventory, work);
                if (initial.canceled()) {
                    return uncommitted(BulkDispatchResult.Status.JOB_CANCELED, work);
                }
                if (!initial.providerModelCurrent()) {
                    return uncommitted(BulkDispatchResult.Status.PREFLIGHT_CHANGED, work);
                }

                BulkCommitPlan unrestrictedPlan = MultiplierPlanner.plan(
                        selection.model(), initial.planningState(), BulkCostPreflight.none(), work);
                if (unrestrictedPlan.isEmpty()) {
                    continue;
                }
                BulkCostPreflight costPreflight = costService.preflight(
                        selection.model(), unrestrictedPlan.multiplier());
                BulkCommitPlan plan = MultiplierPlanner.plan(
                        selection.model(), initial.planningState(), costPreflight, work);
                if (plan.isEmpty()) {
                    continue;
                }

                simulateFinalDelivery(plan, bridge);
                if (bridge.bulk$getJob() != job || bridge.bulk$getInventory() != inventory) {
                    return uncommitted(BulkDispatchResult.Status.PREFLIGHT_CHANGED, work);
                }
                PreflightSnapshot immediatelyBeforeCommit = snapshot(
                        selection, job, bridge, inventory, work);
                if (!initial.equals(immediatelyBeforeCommit)
                        || !immediatelyBeforeCommit.providerModelCurrent()
                        || immediatelyBeforeCommit.canceled()) {
                    return uncommitted(BulkDispatchResult.Status.PREFLIGHT_CHANGED, work);
                }

                BulkDispatchResult result = reserveAndCommit(
                        selection.task(), job, plan, bridge, inventory, work);
                if (result.committed()) {
                    BulkExecutionMetrics.bulkDispatch(result.committedMultiplier());
                }
                return result;
            }
        }
        return uncommitted(
                foundVerifiedProcess
                        ? BulkDispatchResult.Status.NO_CAPACITY
                        : BulkDispatchResult.Status.NO_VERIFIED_PROCESS,
                work);
    }

    private static BulkDispatchResult reserveAndCommit(
            IBulkTaskView task,
            IBulkJobBridge job,
            BulkCommitPlan plan,
            IBulkDispatchBridge bridge,
            ICraftingInventory inventory,
            BulkWorkCounter work) {
        BulkDispatchReservation reservation = bridge.bulk$tryReserveDispatch();
        if (reservation == null) {
            return uncommitted(BulkDispatchResult.Status.NO_CAPACITY, work);
        }
        if (!reservation.isCurrent()) {
            reservation.release();
            return uncommitted(BulkDispatchResult.Status.PREFLIGHT_CHANGED, work);
        }

        try {
            BulkDispatchResult result = commit(task, job, plan, bridge, inventory, work);
            if (result.committed()) {
                reservation.commit();
            } else {
                reservation.release();
            }
            return result;
        } catch (RuntimeException | Error exception) {
            // A throwing commit has unknown ownership/mutation state and will
            // quarantine/cancel through the CPU bridge. Conservatively retain
            // this tick's slot so re-entrancy cannot bypass server protection.
            reservation.commit();
            throw exception;
        }
    }

    private static void simulateFinalDelivery(
            BulkCommitPlan plan, IBulkDispatchBridge bridge) {
        plan.finalDelivery().ifPresent(delivery -> {
            long simulatedAccepted = bridge.bulk$simulateFinalDelivery(
                    delivery.key(), delivery.escrowAmount());
            if (simulatedAccepted < 0 || simulatedAccepted > delivery.escrowAmount()) {
                throw new IllegalStateException(
                        "final destination returned an invalid simulated acceptance: "
                                + simulatedAccepted);
            }
        });
    }

    private static PreflightSnapshot snapshot(
            Selection selection,
            IBulkJobBridge job,
            IBulkDispatchBridge bridge,
            ICraftingInventory inventory,
            BulkWorkCounter work) {
        work.provider();
        BulkExecutionMetrics.verifiedModelCacheLookup();
        boolean providerModelCurrent = selection.provider()
                .getVerifiedProcessModel(selection.pattern())
                .filter(model -> model == selection.model())
                .isPresent();

        long taskRemaining = requireNonNegative(selection.task().remaining(), "task remaining");
        long providerLimit = requireNonNegative(
                selection.provider().getMaxBulkOperations(), "provider limit");
        long serverConfigLimit = requireNonNegative(
                bridge.bulk$getServerConfigLimit(), "server config limit");
        Map<AEKey, Long> inputAvailability = new LinkedHashMap<>();
        for (AEKey key : selection.model().inputsPerOperation().keySet()) {
            work.snapshotKey();
            inputAvailability.put(key, inventoryAmount(inventory, key));
        }

        var outputKeys = new LinkedHashSet<AEKey>();
        for (AEKey key : selection.model().outputsPerOperation().keySet()) {
            work.snapshotKey();
            outputKeys.add(key);
        }
        for (AEKey key : selection.model().remainingItemsPerOperation().keySet()) {
            work.snapshotKey();
            outputKeys.add(key);
        }

        Map<AEKey, Long> waitingFor = new LinkedHashMap<>();
        Map<AEKey, Long> escrow = new LinkedHashMap<>();
        Map<AEKey, Long> cpuInventory = new LinkedHashMap<>();
        for (AEKey key : outputKeys) {
            work.snapshotKey();
            waitingFor.put(key, requireNonNegative(job.bulk$getWaitingFor(key), "waiting amount"));
            escrow.put(key, requireNonNegative(job.bulk$outputEscrow().get(key), "escrow amount"));
            cpuInventory.put(key, inventoryAmount(inventory, key));
        }

        AEKey finalOutputKey = Objects.requireNonNull(
                job.bulk$getFinalOutputKey(), "final output key");
        long remainingFinal = requireNonNegative(
                job.bulk$getRemainingFinalAmount(), "remaining final amount");
        var planningState = new MultiplierPlanner.PlanningState(
                taskRemaining,
                providerLimit,
                serverConfigLimit,
                inputAvailability,
                waitingFor,
                escrow,
                cpuInventory,
                finalOutputKey,
                job.bulk$isStandalone(),
                remainingFinal);

        return new PreflightSnapshot(
                providerModelCurrent,
                job.bulk$isCanceled(),
                planningState);
    }

    private static BulkDispatchResult commit(
            IBulkTaskView task,
            IBulkJobBridge job,
            BulkCommitPlan plan,
            IBulkDispatchBridge bridge,
            ICraftingInventory inventory,
            BulkWorkCounter work) {
        bridge.bulk$beginCommit();
        CommitMutationResult mutationResult;
        boolean outcomeKnown = false;
        try {
            mutationResult = commitMutations(task, job, plan, bridge, inventory, work);
            outcomeKnown = true;
        } finally {
            bridge.bulk$endCommit(outcomeKnown);
        }

        if (!mutationResult.committed()) {
            return uncommitted(BulkDispatchResult.Status.COMMIT_REJECTED, work);
        }

        work.commitStage();
        boolean jobCompleted = bridge.bulk$getJob() == job
                && allTasksComplete(job, work)
                && bridge.bulk$tryFinishJob();

        work.commitStage();
        bridge.bulk$cleanCompletedTasks();

        return new BulkDispatchResult(
                BulkDispatchResult.Status.COMMITTED,
                plan.multiplier(),
                mutationResult.finalAccepted(),
                1,
                jobCompleted,
                work.snapshot());
    }

    private static CommitMutationResult commitMutations(
            IBulkTaskView task,
            IBulkJobBridge job,
            BulkCommitPlan plan,
            IBulkDispatchBridge bridge,
            ICraftingInventory inventory,
            BulkWorkCounter work) {
        work.commitStage();
        if (!plan.costCommitment().tryConsume()) {
            return CommitMutationResult.rejected();
        }

        work.commitStage();
        List<ExtractedInput> extractedInputs = new ArrayList<>(plan.inputsToExtract().size());
        for (var entry : plan.inputsToExtract().entrySet()) {
            work.commitKey();
            long extracted = inventory.extract(entry.getKey(), entry.getValue(), Actionable.MODULATE);
            if (extracted < 0 || extracted > entry.getValue()) {
                throw new IllegalStateException("inventory returned an invalid extracted amount: " + extracted);
            }
            if (extracted > 0) {
                extractedInputs.add(new ExtractedInput(entry.getKey(), extracted));
            }
            if (extracted != entry.getValue()) {
                restoreInputs(extractedInputs, inventory, work);
                plan.costCommitment().restore();
                return CommitMutationResult.rejected();
            }
        }

        if (bridge.bulk$getJob() != job
                || job.bulk$isCanceled()
                || task.remaining() != plan.limits().taskRemaining()) {
            restoreInputs(extractedInputs, inventory, work);
            plan.costCommitment().restore();
            return CommitMutationResult.rejected();
        }

        work.commitStage();
        for (var entry : plan.waitingToRegister().entrySet()) {
            work.commitKey();
            job.bulk$addWaitingFor(entry.getKey(), entry.getValue());
        }

        work.commitStage();
        task.decrement(plan.taskDecrement());

        work.commitStage();
        for (var entry : plan.outputsToMaterialize().entrySet()) {
            work.commitKey();
            job.bulk$materializeWaitingToEscrow(entry.getKey(), entry.getValue());
        }

        work.commitStage();
        for (var entry : plan.intermediateOutputsToTransfer().entrySet()) {
            work.commitKey();
            bridge.bulk$acceptIntermediateFromEscrow(entry.getKey(), entry.getValue());
        }

        work.commitStage();
        long finalAccepted = plan.finalDelivery()
                .map(delivery -> {
                    work.commitKey();
                    return bridge.bulk$deliverFinalFromEscrow(
                            delivery.key(), delivery.escrowAmount());
                })
                .orElse(0L);
        if (finalAccepted < 0
                || plan.finalDelivery()
                        .map(delivery -> finalAccepted > delivery.escrowAmount())
                        .orElse(finalAccepted != 0)) {
            throw new IllegalStateException(
                    "final destination returned an invalid committed acceptance: " + finalAccepted);
        }

        work.commitStage();
        bridge.bulk$markDirty();
        return new CommitMutationResult(true, finalAccepted);
    }

    private static void restoreInputs(
            List<ExtractedInput> extractedInputs,
            ICraftingInventory inventory,
            BulkWorkCounter work) {
        for (int index = extractedInputs.size() - 1; index >= 0; index--) {
            work.commitKey();
            ExtractedInput extracted = extractedInputs.get(index);
            inventory.insert(extracted.key(), extracted.amount(), Actionable.MODULATE);
        }
    }

    private static boolean allTasksComplete(IBulkJobBridge job, BulkWorkCounter work) {
        Iterable<IBulkTaskView> tasks = Objects.requireNonNull(job.bulk$tasks(), "job tasks");
        for (IBulkTaskView task : tasks) {
            work.task();
            long remaining = Objects.requireNonNull(task, "task").remaining();
            if (remaining < 0) {
                throw new IllegalStateException("task remaining must be non-negative");
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    private static long inventoryAmount(ICraftingInventory inventory, AEKey key) {
        long amount = inventory.extract(
                Objects.requireNonNull(key, "inventory key"),
                Long.MAX_VALUE,
                Actionable.SIMULATE);
        return requireNonNegative(amount, "inventory amount");
    }

    private static BulkDispatchResult uncommitted(
            BulkDispatchResult.Status status,
            BulkWorkCounter work) {
        return new BulkDispatchResult(status, 0, 0, 0, false, work.snapshot());
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalStateException(name + " must be non-negative");
        }
        return value;
    }

    private record Selection(
            IBulkTaskView task,
            IPatternDetails pattern,
            IBulkCraftingProvider provider,
            VerifiedProcessModel model) {
    }

    private record PreflightSnapshot(
            boolean providerModelCurrent,
            boolean canceled,
            MultiplierPlanner.PlanningState planningState) {
        private PreflightSnapshot {
            Objects.requireNonNull(planningState, "planningState");
        }
    }

    private record CommitMutationResult(boolean committed, long finalAccepted) {
        private static CommitMutationResult rejected() {
            return new CommitMutationResult(false, 0);
        }
    }

    private record ExtractedInput(AEKey key, long amount) {
        private ExtractedInput {
            Objects.requireNonNull(key, "key");
            if (amount <= 0) {
                throw new IllegalArgumentException("extracted amount must be positive");
            }
        }
    }
}
