package dev.appliedcausality.internal.cpu;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Server-thread guard against synchronous callbacks during Bulk commit and delivery. */
public final class BulkDeliveryGuard {
    private static final String DATA_KEY = "applied_causality_transaction";
    private boolean committing;
    private boolean settling;
    private boolean cancelRequested;
    private boolean failed;
    private boolean draining;
    private int teardownDepth;
    private final Map<Object, Runnable> teardowns = new LinkedHashMap<>();

    /**
     * Stable saves need no marker. Reentrant saves are diagnostic snapshots,
     * never authorization to replay an offer whose exact acceptance is unknown.
     * CPU scope also covers finishJob's inventory return after job deletion.
     */
    public void writeToNBT(CompoundTag data, boolean finishingJob) {
        if (blocksReentry() || finishingJob || teardownDepth != 0) {
            var state = new CompoundTag();
            state.putInt("dataVersion", 1);
            state.putBoolean("uncertain", true);
            data.put(DATA_KEY, state);
        } else {
            data.remove(DATA_KEY);
        }
    }

    /** Migration insertion point: absent sidecars are pre-Phase17 stable saves. */
    public void readFromNBT(CompoundTag data) {
        if (!data.contains(DATA_KEY)) return;
        // Preserve unknown/future records as blocked state instead of throwing
        // from BlockEntity load and letting Minecraft discard their owner.
        var state = data.getCompound(DATA_KEY);
        failed |= !data.contains(DATA_KEY, Tag.TAG_COMPOUND)
                || !state.contains("dataVersion", Tag.TAG_INT)
                || state.getInt("dataVersion") != 1
                || !state.contains("uncertain", Tag.TAG_BYTE)
                || state.getByte("uncertain") != 0;
    }

    /** Preserve the whole AE2 lifecycle operation, including drop/clear and reference changes. */
    public boolean beginTeardown(Object operation, Runnable continuation, boolean finishingJob) {
        requireKnownOutcome();
        if (committing || settling || finishingJob) {
            // Repeated requests for the same operation retain the latest arguments.
            teardowns.put(Objects.requireNonNull(operation), Objects.requireNonNull(continuation));
            return false;
        }
        teardownDepth++;
        return true;
    }

    public void endTeardown() {
        if (teardownDepth <= 0) {
            throw new IllegalStateException("unbalanced CPU teardown");
        }
        teardownDepth--;
        drainTeardowns();
    }

    public void drainTeardowns() {
        requireKnownOutcome();
        if (committing || settling || draining || teardownDepth != 0) {
            return;
        }
        draining = true;
        try {
            while (!teardowns.isEmpty()) {
                var iterator = teardowns.values().iterator();
                var continuation = iterator.next();
                iterator.remove();
                continuation.run();
            }
        } finally {
            draining = false;
        }
    }

    public boolean blocksReentry() {
        return committing || settling || failed;
    }

    public boolean isQuarantined() {
        return failed;
    }

    /** Begins the mutation-only interval after all practical preflight. */
    public void beginCommit() {
        requireKnownOutcome();
        if (committing || settling) {
            throw new IllegalStateException("nested Bulk commit");
        }
        committing = true;
    }

    /**
     * Publishes a known commit outcome before replaying deferred lifecycle work.
     * Unknown ownership permanently blocks retries, cancellation and teardown.
     */
    public void endCommit(boolean outcomeKnown, Runnable cancel) {
        Objects.requireNonNull(cancel, "cancel");
        if (!committing) {
            throw new IllegalStateException("unbalanced Bulk commit");
        }
        committing = false;
        if (!outcomeKnown || failed) {
            failed = true;
            return;
        }
        runDeferredCancellation(cancel);
        drainTeardowns();
    }

    /** Returns true when the caller must defer cancellation without changing ownership. */
    public boolean deferCancellation() {
        requireKnownOutcome();
        if (!committing && !settling) {
            return false;
        }
        cancelRequested = true;
        return true;
    }

    /**
     * Settlement includes exact accepted subtraction, remaining, surplus and
     * listener/display updates. Only after it returns may cancellation move
     * the remainder. Nested delivery makes no offer and accepts zero.
     */
    public long settle(LongSupplier settlement, Runnable cancel) {
        return settle(settlement, cancel, false);
    }

    /**
     * Covers the whole stock inventory-return operation, including listener
     * notifications and subtraction after network acceptance. A finishJob caller
     * owns lifecycle replay until its TAIL, even though it has already cleared job.
     */
    public void returnInventory(Runnable transfer, Runnable cancel, boolean finishingJob) {
        Objects.requireNonNull(transfer, "transfer");
        settle(() -> {
            transfer.run();
            return 0;
        }, cancel, finishingJob);
    }

    private long settle(LongSupplier settlement, Runnable cancel, boolean finishingJob) {
        Objects.requireNonNull(settlement, "settlement");
        Objects.requireNonNull(cancel, "cancel");
        requireKnownOutcome();
        if (settling) {
            return 0;
        }
        settling = true;
        final long accepted;
        try {
            accepted = settlement.getAsLong();
        } catch (RuntimeException | Error exception) {
            // A destination that throws after external mutation supplies no
            // exact acceptance. Never guess zero, retry, or flush full escrow.
            failed = true;
            throw exception;
        } finally {
            settling = false;
        }
        if (!committing && !finishingJob) {
            runDeferredCancellation(cancel);
            drainTeardowns();
        }
        return accepted;
    }

    private void runDeferredCancellation(Runnable cancel) {
        if (!cancelRequested) {
            return;
        }
        cancelRequested = false;
        try {
            cancel.run();
        } catch (RuntimeException | Error exception) {
            failed = true;
            throw exception;
        }
    }

    private void requireKnownOutcome() {
        if (failed) {
            throw new IllegalStateException("Bulk commit or delivery failed; ownership outcome is unknown");
        }
    }
}
