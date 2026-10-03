package dev.appliedcausality.internal.budget;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGridServiceProvider;

/**
 * AE2-managed, per-grid implementation of the Phase 18 dispatch budget.
 *
 * <p>AE2 invokes {@link #onServerStartTick()} once per grid before crafting
 * execution at server end tick. No state is persisted: a budget applies only
 * to the server tick in which it was consumed.</p>
 */
public final class BulkDispatchBudgetService implements IBulkDispatchBudget, IGridServiceProvider {
    private int committed;
    private int reserved;

    /** Required public no-argument constructor for {@code GridServices}. */
    public BulkDispatchBudgetService() {
    }

    @Override
    public boolean hasCapacity() {
        return committed + reserved < MAX_BULK_DISPATCHES_PER_TICK;
    }

    @Override
    @Nullable
    public BulkDispatchReservation tryReserve() {
        if (!hasCapacity()) {
            return null;
        }
        reserved++;
        return new Reservation();
    }

    @Override
    public void onServerStartTick() {
        committed = 0;
        reserved = 0;
    }

    int committedForTest() {
        return committed;
    }

    int reservedForTest() {
        return reserved;
    }

    private final class Reservation implements BulkDispatchReservation {
        private boolean resolved;

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
            if (resolved) {
                throw new IllegalStateException("Bulk dispatch reservation was already resolved");
            }
            if (reserved <= 0) {
                throw new IllegalStateException("Bulk dispatch reservation is no longer current");
            }
            resolved = true;
            reserved--;
        }
    }
}
