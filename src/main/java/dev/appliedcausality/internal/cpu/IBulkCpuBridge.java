package dev.appliedcausality.internal.cpu;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import appeng.crafting.inv.ICraftingInventory;

/**
 * Internal CPU surface shared by Bulk execution integrations.
 */
public interface IBulkCpuBridge {
    /**
     * Begins the mutation-only part of one Bulk commit. Synchronous listeners
     * must not be allowed to cancel or tear down the CPU until it ends.
     */
    void bulk$beginCommit();

    /**
     * Ends the mutation-only part of one Bulk commit. A false value means an
     * exception left ownership uncertain and deferred lifecycle work must not
     * be replayed.
     */
    void bulk$endCommit(boolean outcomeKnown);

    @Nullable
    IBulkJobBridge bulk$getJob();

    ICraftingInventory bulk$getInventory();

    void bulk$markDirty();

    /**
     * Side-effect-free planning hint. The prospective output need not exist in
     * escrow yet; implementations must also preflight ownership of any rounded
     * recipe surplus that cannot be delivered to the requester.
     */
    long bulk$simulateFinalDelivery(AEKey key, long amount);

    /**
     * Offers escrowed final output, advances request remaining only by the
     * requester's exact accepted amount, and retains rounded surplus.
     */
    long bulk$deliverFinalFromEscrow(AEKey key, long amount);

    void bulk$acceptIntermediateFromEscrow(AEKey key, long amount);

    boolean bulk$tryFinishJob();
}
