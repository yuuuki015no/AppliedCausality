package dev.appliedcausality.internal.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BulkDispatchBudgetServiceTest {
    @Test
    void committedDispatchesAreBoundedPerGridAndResetAtServerTickStart() {
        var firstGrid = new BulkDispatchBudgetService();
        var secondGrid = new BulkDispatchBudgetService();

        for (int dispatch = 0; dispatch < IBulkDispatchBudget.MAX_BULK_DISPATCHES_PER_TICK; dispatch++) {
            BulkDispatchReservation reservation = firstGrid.tryReserve();
            assertNotNull(reservation, "fixed per-grid dispatch budget ended early");
            reservation.commit();
        }

        assertEquals(IBulkDispatchBudget.MAX_BULK_DISPATCHES_PER_TICK, firstGrid.committedForTest());
        assertFalse(firstGrid.hasCapacity());
        assertNull(firstGrid.tryReserve());
        assertTrue(secondGrid.hasCapacity(), "independent grids must not share a dispatch budget");

        firstGrid.onServerStartTick();
        assertEquals(0, firstGrid.committedForTest());
        assertEquals(0, firstGrid.reservedForTest());
        assertTrue(firstGrid.hasCapacity(), "server tick reset did not restore per-grid capacity");
    }

    @Test
    void rejectedAndDoubleResolvedReservationsCannotOverconsumeOrBypassBudget() {
        var budget = new BulkDispatchBudgetService();
        BulkDispatchReservation rejected = budget.tryReserve();
        assertNotNull(rejected);
        rejected.release();
        assertEquals(0, budget.committedForTest());
        assertEquals(0, budget.reservedForTest());

        BulkDispatchReservation committed = budget.tryReserve();
        assertNotNull(committed);
        committed.commit();
        assertEquals(1, budget.committedForTest());
        assertThrows(IllegalStateException.class, committed::release);
    }
}
