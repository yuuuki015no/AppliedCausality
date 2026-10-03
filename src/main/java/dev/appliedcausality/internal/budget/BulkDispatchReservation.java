package dev.appliedcausality.internal.budget;

/**
 * A single dispatch slot reserved while a Bulk commit is in progress.
 *
 * <p>A reservation is resolved exactly once. It is committed only after the
 * Bulk transaction has committed; a known rejected transaction releases it.
 * This closes the re-entrant window between preflight and commit without
 * charging rejected planning attempts to the per-grid budget.</p>
 */
public interface BulkDispatchReservation {
    /**
     * Whether the CPU is still attached to the grid that supplied this slot.
     * The engine checks this immediately before commit, after all planning
     * callbacks have completed.
     */
    default boolean isCurrent() {
        return true;
    }

    /** Marks the reserved slot as one committed Bulk dispatch. */
    void commit();

    /** Returns a known-uncommitted slot to the current tick's budget. */
    void release();

    static BulkDispatchReservation unlimited() {
        return UnlimitedReservation.INSTANCE;
    }

    enum UnlimitedReservation implements BulkDispatchReservation {
        INSTANCE;

        @Override
        public void commit() {
        }

        @Override
        public void release() {
        }
    }
}
