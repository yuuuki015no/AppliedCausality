package dev.appliedcausality.internal.cpu;

import appeng.api.crafting.IPatternDetails;
import dev.appliedcausality.internal.budget.BulkDispatchReservation;
import dev.appliedcausality.internal.provider.IBulkCraftingProvider;

/**
 * Execution surface shared by in-memory tests and production CPU integrations.
 */
public interface IBulkDispatchBridge extends IBulkCpuBridge {
    /**
     * Returns providers in the scheduler's deterministic preference order.
     */
    Iterable<? extends IBulkCraftingProvider> bulk$getProviders(IPatternDetails pattern);

    /**
     * Fast, side-effect-free budget availability check before candidate work.
     * Test/in-memory bridges are unlimited unless they opt into a budget.
     */
    default boolean bulk$hasDispatchCapacity() {
        return true;
    }

    /**
     * Reserves one operation immediately before a Bulk commit. A {@code null}
     * result means the CPU must retain the ordinary AE2 execution path.
     */
    default BulkDispatchReservation bulk$tryReserveDispatch() {
        return BulkDispatchReservation.unlimited();
    }

    long bulk$getServerConfigLimit();

    /**
     * Performs the scheduler's normal removal of zero-remaining task entries.
     */
    void bulk$cleanCompletedTasks();
}
