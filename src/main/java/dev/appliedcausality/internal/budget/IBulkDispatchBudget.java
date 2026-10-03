package dev.appliedcausality.internal.budget;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGridService;

/** Internal per-grid dispatch budget; it is not part of the addon API. */
public interface IBulkDispatchBudget extends IGridService {
    int MAX_BULK_DISPATCHES_PER_TICK = 4_096;

    /**
     * Returns whether this grid can reserve one additional Bulk dispatch in
     * the current server tick. This query does not mutate budget state.
     */
    boolean hasCapacity();

    /**
     * Reserves one slot, or returns {@code null} when this grid's current tick
     * budget is exhausted.
     */
    @Nullable
    BulkDispatchReservation tryReserve();
}
