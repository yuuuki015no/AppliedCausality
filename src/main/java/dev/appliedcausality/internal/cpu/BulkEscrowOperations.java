package dev.appliedcausality.internal.cpu;

import java.util.Objects;

import appeng.api.stacks.AEKey;

/**
 * Small exact operations over escrow ownership, separated from CPU Mixins.
 */
public final class BulkEscrowOperations {
    private BulkEscrowOperations() {
    }

    @FunctionalInterface
    public interface ExactDestination {
        long insert(AEKey key, long amount);
    }

    @FunctionalInterface
    public interface FailFreeDestination {
        void insert(AEKey key, long amount);
    }

    /** The caller must protect escrow and job lifetime from destination reentry
     * until this exact acceptance and its associated progress are settled. */
    public static long deliver(
            IBulkOutputEscrow escrow,
            AEKey key,
            long offered,
            ExactDestination destination) {
        Objects.requireNonNull(escrow, "escrow");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(destination, "destination");
        requireAvailable(escrow, key, offered);
        if (offered == 0) {
            return 0;
        }

        long accepted = destination.insert(key, offered);
        if (accepted < 0 || accepted > offered) {
            throw new IllegalStateException("Destination returned an invalid accepted amount: " + accepted);
        }
        escrow.remove(key, accepted);
        return accepted;
    }

    public static void transferAll(IBulkOutputEscrow escrow, FailFreeDestination destination) {
        Objects.requireNonNull(escrow, "escrow");
        Objects.requireNonNull(destination, "destination");

        for (var entry : escrow.entries()) {
            escrow.remove(entry.what(), entry.amount());
            destination.insert(entry.what(), entry.amount());
        }
    }

    public static boolean canFinish(long remainingFinalAmount, IBulkOutputEscrow escrow) {
        if (remainingFinalAmount < 0) {
            throw new IllegalArgumentException("remainingFinalAmount must be non-negative");
        }
        return remainingFinalAmount == 0 && escrow.isEmpty();
    }

    private static void requireAvailable(IBulkOutputEscrow escrow, AEKey key, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        if (amount > escrow.get(key)) {
            throw new IllegalArgumentException("amount exceeds escrow ownership");
        }
    }
}
