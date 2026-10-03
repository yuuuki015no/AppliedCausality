package dev.appliedcausality.internal.cpu;

import appeng.api.stacks.AEKey;
import net.minecraft.nbt.CompoundTag;

/**
 * Internal per-job state surface used by the Bulk engine.
 */
public interface IBulkJobBridge {
    Iterable<IBulkTaskView> bulk$tasks();

    void bulk$cleanCompletedTasks();

    long bulk$getWaitingFor(AEKey key);

    void bulk$addWaitingFor(AEKey key, long amount);

    void bulk$materializeWaitingToEscrow(AEKey key, long amount);

    IBulkOutputEscrow bulk$outputEscrow();

    boolean bulk$isFinalOutput(AEKey key);

    AEKey bulk$getFinalOutputKey();

    boolean bulk$isStandalone();

    boolean bulk$isCanceled();

    long bulk$getRemainingFinalAmount();

    void bulk$decrementRemainingFinal(long amount);

    long bulk$simulateRequesterDelivery(AEKey key, long amount);

    long bulk$deliverToRequester(AEKey key, long amount);

    void bulk$readOutputEscrow(CompoundTag jobData);

    void bulk$writeOutputEscrow(CompoundTag jobData);
}
