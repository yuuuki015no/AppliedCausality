package dev.appliedcausality.internal.cpu;

import java.util.Objects;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import dev.appliedcausality.internal.budget.BulkDispatchReservation;
import dev.appliedcausality.internal.budget.IBulkDispatchBudget;

/** Resolves and binds a Bulk slot to the CPU's grid at reservation time. */
final class CurrentGridDispatchBudget {
    private CurrentGridDispatchBudget() {
    }

    static boolean hasCapacity(Supplier<@Nullable IGrid> currentGrid) {
        var budget = find(currentGrid.get());
        return budget != null && budget.hasCapacity();
    }

    @Nullable
    static BulkDispatchReservation tryReserve(Supplier<@Nullable IGrid> currentGrid) {
        IGrid grid = currentGrid.get();
        IBulkDispatchBudget budget = find(grid);
        if (budget == null) {
            return null;
        }
        BulkDispatchReservation reservation = budget.tryReserve();
        return reservation == null ? null : new GridBoundReservation(currentGrid, grid, reservation);
    }

    @Nullable
    static IBulkDispatchBudget find(@Nullable IGrid grid) {
        if (grid == null) {
            return null;
        }
        try {
            return grid.getService(IBulkDispatchBudget.class);
        } catch (IllegalArgumentException ignored) {
            // AE2 Grid#getService throws for an unregistered service. Bulk is
            // optional at this boundary, so retain the original CPU path.
            return null;
        }
    }

    private static final class GridBoundReservation implements BulkDispatchReservation {
        private final Supplier<@Nullable IGrid> currentGrid;
        private final IGrid reservedGrid;
        private final BulkDispatchReservation delegate;

        private GridBoundReservation(
                Supplier<@Nullable IGrid> currentGrid,
                IGrid reservedGrid,
                BulkDispatchReservation delegate) {
            this.currentGrid = Objects.requireNonNull(currentGrid, "currentGrid");
            this.reservedGrid = Objects.requireNonNull(reservedGrid, "reservedGrid");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public boolean isCurrent() {
            return currentGrid.get() == reservedGrid;
        }

        @Override
        public void commit() {
            delegate.commit();
        }

        @Override
        public void release() {
            delegate.release();
        }
    }
}
