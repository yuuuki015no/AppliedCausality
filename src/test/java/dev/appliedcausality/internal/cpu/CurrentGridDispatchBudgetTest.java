package dev.appliedcausality.internal.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import appeng.api.networking.IGrid;
import dev.appliedcausality.internal.budget.BulkDispatchReservation;
import dev.appliedcausality.internal.budget.IBulkDispatchBudget;

class CurrentGridDispatchBudgetTest {
    @Test
    void reservationUsesCurrentGridAfterCallbackAndRejectsLaterGridMovement() {
        var firstBudget = new TestBudget();
        var secondBudget = new TestBudget();
        IGrid firstGrid = gridWith(firstBudget);
        IGrid secondGrid = gridWith(secondBudget);
        var currentGrid = new AtomicReference<IGrid>(firstGrid);

        assertTrue(CurrentGridDispatchBudget.hasCapacity(currentGrid::get));
        // Model the synchronous requester SIMULATE callback moving the CPU.
        currentGrid.set(secondGrid);
        BulkDispatchReservation reservation = CurrentGridDispatchBudget.tryReserve(currentGrid::get);

        assertNotNull(reservation);
        assertEquals(0, firstBudget.reserved);
        assertEquals(1, secondBudget.reserved);
        assertTrue(reservation.isCurrent());

        currentGrid.set(null);
        assertFalse(reservation.isCurrent());
        reservation.release();
        assertEquals(0, secondBudget.reserved);
    }

    @Test
    void unregisteredGridServiceDeclinesBulkInsteadOfThrowing() {
        IGrid unregisteredGrid = (IGrid) Proxy.newProxyInstance(
                IGrid.class.getClassLoader(),
                new Class<?>[] { IGrid.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getService")) {
                        throw new IllegalArgumentException("Service " + args[0] + " is not registered");
                    }
                    throw new AssertionError("unexpected grid call " + method);
                });

        assertNull(CurrentGridDispatchBudget.find(unregisteredGrid));
        assertFalse(CurrentGridDispatchBudget.hasCapacity(() -> unregisteredGrid));
        assertNull(CurrentGridDispatchBudget.tryReserve(() -> unregisteredGrid));
    }

    private static IGrid gridWith(IBulkDispatchBudget budget) {
        return (IGrid) Proxy.newProxyInstance(
                IGrid.class.getClassLoader(),
                new Class<?>[] { IGrid.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getService")) {
                        if (args[0] == IBulkDispatchBudget.class) {
                            return budget;
                        }
                        throw new IllegalArgumentException("Service " + args[0] + " is not registered");
                    }
                    throw new AssertionError("unexpected grid call " + method);
                });
    }

    private static final class TestBudget implements IBulkDispatchBudget {
        private int committed;
        private int reserved;

        @Override
        public boolean hasCapacity() {
            return true;
        }

        @Override
        public BulkDispatchReservation tryReserve() {
            reserved++;
            return new BulkDispatchReservation() {
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
                    if (resolved || reserved <= 0) {
                        throw new IllegalStateException("invalid reservation");
                    }
                    resolved = true;
                    reserved--;
                }
            };
        }
    }
}
