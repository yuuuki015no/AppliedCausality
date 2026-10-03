package dev.appliedcausality.internal.cpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BulkDeliveryGuardTest {
    @Test
    void stockReturnSettlesBeforeTeardownButFinishCallerRetainsLifecycleOwnership() {
        for (boolean finishing : new boolean[] {false, true}) {
            var guard = new BulkDeliveryGuard();
            var events = new ArrayList<String>();
            guard.returnInventory(() -> {
                var snapshot = new net.minecraft.nbt.CompoundTag();
                guard.writeToNBT(snapshot, finishing);
                assertQuarantined(snapshot);
                assertFalse(guard.beginTeardown(new Object(), () -> events.add("teardown"), finishing));
                guard.returnInventory(() -> fail("nested stock return"), () -> fail("nested cancel"), finishing);
                events.add("subtracted actual acceptance");
            }, () -> fail("unexpected cancel"), finishing);
            if (finishing) {
                assertEquals(List.of("subtracted actual acceptance"), events);
                events.add("finishJob tail");
                guard.drainTeardowns();
                assertEquals(List.of("subtracted actual acceptance", "finishJob tail", "teardown"), events);
            } else {
                assertEquals(List.of("subtracted actual acceptance", "teardown"), events);
            }
            var settled = new net.minecraft.nbt.CompoundTag();
            guard.writeToNBT(settled, false);
            assertTrue(settled.isEmpty());
        }
    }

    @Test
    void stockReturnFailureRetainsUnknownOutcomeAndOriginalException() {
        var guard = new BulkDeliveryGuard();
        var failure = new IllegalStateException("network accepted before throwing");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> guard.returnInventory(() -> {
            assertFalse(guard.beginTeardown(new Object(), () -> fail("unsettled teardown"), false));
            throw failure;
        }, () -> fail("unsettled cancellation"), false)));
        assertTrue(guard.isQuarantined());
        var saved = new net.minecraft.nbt.CompoundTag();
        guard.writeToNBT(saved, false);
        assertQuarantined(saved);
    }

    @Test
    void snapshotsDuringCommitAndSettlementStayQuarantinedAcrossRepeatedLoads() {
        var guard = new BulkDeliveryGuard();
        guard.beginCommit();
        var snapshot = new net.minecraft.nbt.CompoundTag();
        guard.writeToNBT(snapshot, false);
        assertQuarantined(snapshot);
        guard.settle(() -> {
            guard.writeToNBT(snapshot, false);
            assertQuarantined(snapshot);
            return 2;
        }, () -> fail("unexpected cancel"));
        guard.endCommit(true, () -> fail("unexpected cancel"));
        guard.writeToNBT(snapshot, false);
        assertTrue(snapshot.isEmpty(), "stable settlement must clear the transient marker");
        var restored = new BulkDeliveryGuard();
        restored.readFromNBT(snapshot);
        assertFalse(restored.blocksReentry());
        guard.writeToNBT(snapshot, true);
        assertQuarantined(snapshot);
    }

    @Test
    void unknownOrMalformedGuardVersionsQuarantineInsteadOfDiscardingOwnerOnLoad() {
        for (var invalid : java.util.List.of(net.minecraft.nbt.StringTag.valueOf("invalid"),
                new net.minecraft.nbt.CompoundTag())) {
            var data = new net.minecraft.nbt.CompoundTag();
            data.put("applied_causality_transaction", invalid);
            assertQuarantined(data);
        }
        var guard = new BulkDeliveryGuard();
        guard.beginCommit();
        guard.endCommit(false, () -> fail("unknown commit canceled"));
        var data = new net.minecraft.nbt.CompoundTag();
        guard.writeToNBT(data, false);
        data.getCompound("applied_causality_transaction").putInt("dataVersion", 2);
        assertQuarantined(data);
    }

    private static void assertQuarantined(net.minecraft.nbt.CompoundTag data) {
        for (int reload = 0; reload < 2; reload++) {
            var restored = new BulkDeliveryGuard();
            restored.readFromNBT(data);
            assertTrue(restored.blocksReentry());
            restored.readFromNBT(new net.minecraft.nbt.CompoundTag());
            assertTrue(restored.blocksReentry(), "loading an old stable snapshot cleared quarantine");
            assertThrows(IllegalStateException.class, restored::deferCancellation);
            assertThrows(IllegalStateException.class, restored::beginCommit);
            assertThrows(IllegalStateException.class,
                    () -> restored.beginTeardown(new Object(), () -> fail("unknown output moved"), false));
            data = new net.minecraft.nbt.CompoundTag();
            restored.writeToNBT(data, false);
        }
    }

    @Test
    void cancellationAndTeardownWaitForTheWholeCommitIncludingSettlement() {
        var guard = new BulkDeliveryGuard();
        var events = new ArrayList<String>();
        guard.beginCommit();
        assertTrue(guard.blocksReentry());
        assertTrue(guard.deferCancellation());
        assertFalse(guard.beginTeardown(new Object(), () -> events.add("teardown"), false));
        assertEquals(3, guard.settle(() -> {
            events.add("settled");
            assertTrue(guard.deferCancellation());
            return 3;
        }, () -> fail("settlement ended before the outer commit")));
        assertEquals(List.of("settled"), events);

        events.add("materialized");
        guard.endCommit(true, () -> events.add("cancel"));

        assertEquals(List.of("settled", "materialized", "cancel", "teardown"), events);
        assertFalse(guard.blocksReentry());
        assertFalse(guard.deferCancellation());
    }

    @Test
    void unknownCommitOutcomeNeverRunsDeferredLifecycleWork() {
        var guard = new BulkDeliveryGuard();
        guard.beginCommit();
        assertTrue(guard.deferCancellation());
        assertFalse(guard.beginTeardown(new Object(), () -> fail("unknown ownership was torn down"), false));
        guard.endCommit(false, () -> fail("unknown ownership was canceled"));

        assertTrue(guard.blocksReentry());
        assertThrows(IllegalStateException.class, guard::deferCancellation);
        assertThrows(IllegalStateException.class, guard::beginCommit);
    }

    @Test
    void teardownWaitsForSettlementAndKeepsLatestArguments() {
        var guard = new BulkDeliveryGuard();
        var events = new ArrayList<String>();
        var status = new Object();
        assertEquals(2, guard.settle(() -> {
            assertFalse(guard.beginTeardown(status, () -> fail("stale replacement"), false));
            assertFalse(guard.beginTeardown(status, () -> events.add("latest replacement"), false));
            guard.deferCancellation();
            events.add("settled 2");
            return 2;
        }, () -> events.add("canceled remaining 8")));
        assertEquals(List.of("settled 2", "canceled remaining 8", "latest replacement"), events);
    }

    @Test
    void finishCallbackCannotDrainInsideStockTeardownOrReenterDrain() {
        var guard = new BulkDeliveryGuard();
        var events = new ArrayList<String>();
        assertTrue(guard.beginTeardown(new Object(), () -> fail("outer queued"), false));
        assertFalse(guard.beginTeardown(new Object(), () -> {
            events.add("queued break start");
            assertTrue(guard.beginTeardown(new Object(), () -> fail("replay queued"), false));
            guard.endTeardown(); // Must not recursively drain the next operation.
            events.add("queued break end");
        }, true));
        assertFalse(guard.beginTeardown(new Object(), () -> events.add("queued destroy"), true));
        guard.drainTeardowns(); // finishJob TAIL inside the original break body
        assertTrue(events.isEmpty());
        events.add("outer drop/clear end");
        guard.endTeardown();
        assertEquals(List.of("outer drop/clear end", "queued break start", "queued break end", "queued destroy"), events);
    }

    @Test
    void cancellationWaitsForSettlementAndRepeatedRequestsCoalesce() {
        var guard = new BulkDeliveryGuard();
        var events = new ArrayList<String>();
        assertFalse(guard.deferCancellation());
        assertEquals(2, guard.settle(() -> {
            events.add("destination accepted 2");
            assertTrue(guard.deferCancellation());
            assertTrue(guard.deferCancellation());
            events.add("escrow and remaining settled");
            assertTrue(guard.blocksReentry());
            return 2;
        }, () -> {
            assertFalse(guard.blocksReentry());
            events.add("cancel remainder");
        }));
        assertEquals(List.of("destination accepted 2", "escrow and remaining settled", "cancel remainder"), events);
        assertFalse(guard.deferCancellation());
        assertEquals(1, guard.settle(() -> 1, () -> fail("stale cancellation leaked into another delivery")));
    }

    @Test
    void nestedDeliveryCannotOfferTheSameEscrowAgain() {
        var guard = new BulkDeliveryGuard();
        assertEquals(2, guard.settle(() -> {
            assertEquals(0, guard.settle(() -> {
                fail("nested destination was invoked");
                return 2;
            }, () -> fail("nested cancel ran")));
            return 2;
        }, () -> fail("unexpected cancel")));
    }

    @Test
    void unknownOutcomePropagatesAndNeverFlushesOrRetries() {
        var guard = new BulkDeliveryGuard();
        var failure = new IllegalStateException("destination failed after side effects");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> guard.settle(() -> {
            guard.deferCancellation();
            throw failure;
        }, () -> fail("unknown acceptance must not flush full escrow"))));
        assertTrue(guard.blocksReentry());
        assertThrows(IllegalStateException.class, guard::deferCancellation);
        assertThrows(IllegalStateException.class,
                () -> guard.beginTeardown(new Object(), () -> fail("unknown output dropped"), false));
        assertThrows(IllegalStateException.class,
                () -> guard.settle(() -> fail("unknown delivery was retried"), () -> fail("cancel ran")));
    }
}
