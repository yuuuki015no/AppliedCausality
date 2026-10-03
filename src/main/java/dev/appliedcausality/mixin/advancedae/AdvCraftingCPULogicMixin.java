package dev.appliedcausality.mixin.advancedae;

import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.crafting.execution.CraftingSubmitResult;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.service.CraftingService;
import dev.appliedcausality.internal.bulk.BulkDispatchEngine;
import dev.appliedcausality.internal.bulk.BulkLongMath;
import dev.appliedcausality.internal.cpu.BulkDeliveryGuard;
import dev.appliedcausality.internal.cpu.BulkEscrowOperations;
import dev.appliedcausality.internal.cpu.IBulkCpuBridge;
import dev.appliedcausality.internal.cpu.IBulkCpuLifecycle;
import dev.appliedcausality.internal.cpu.IBulkJobBridge;
import dev.appliedcausality.internal.cpu.StandardAe2DispatchBridge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic;
import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;

/** Thin AdvancedAE adapter over the common Bulk transaction engine. */
@Mixin(value = AdvCraftingCPULogic.class, remap = false)
public abstract class AdvCraftingCPULogicMixin implements IBulkCpuBridge, IBulkCpuLifecycle {
    @Shadow(remap = false)
    @Final
    private AdvCraftingCPU cpu;

    @Shadow(remap = false)
    @Nullable
    private ExecutingCraftingJob job;

    @Shadow(remap = false)
    @Final
    private ListCraftingInventory inventory;

    @Unique
    private final BulkDeliveryGuard bulk$delivery = new BulkDeliveryGuard();

    @Unique
    private boolean bulk$finishingJob;

    @Unique
    private boolean bulk$invokeStockInventoryReturn;

    @Shadow(remap = false)
    public abstract void storeItems();

    @Inject(method = "storeItems()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$guardInventoryReturn(CallbackInfo callback) {
        if (bulk$invokeStockInventoryReturn) {
            // One invocation may enter the original body. Consume before any
            // listener/network callback can recursively call storeItems().
            bulk$invokeStockInventoryReturn = false;
            return;
        }
        if (bulk$delivery.blocksReentry()) {
            callback.cancel();
            return;
        }
        bulk$delivery.returnInventory(() -> {
            if (!inventory.list.isEmpty()) {
                bulk$markDirty();
            }
            bulk$invokeStockInventoryReturn = true;
            try {
                storeItems();
            } finally {
                bulk$invokeStockInventoryReturn = false;
            }
        }, this::cancel, bulk$finishingJob);
        callback.cancel();
    }

    @Shadow(remap = false)
    public abstract void cancel();

    @Inject(method = "trySubmitJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$rejectSubmissionDuringUnsettledOwnership(
            CallbackInfoReturnable<ICraftingSubmitResult> callback) {
        if (bulk$delivery.blocksReentry() || bulk$finishingJob) {
            callback.setReturnValue(CraftingSubmitResult.CPU_BUSY);
        }
    }

    @Override
    public void bulk$beginCommit() {
        bulk$delivery.beginCommit();
        bulk$markDirty();
    }

    @Override
    public boolean bulk$isQuarantined() {
        return bulk$delivery.isQuarantined();
    }

    @Override
    public void bulk$endCommit(boolean outcomeKnown) {
        bulk$delivery.endCommit(outcomeKnown, this::cancel);
    }

    @Override
    public boolean bulk$beginTeardown(Object operation, Runnable continuation) {
        return bulk$delivery.beginTeardown(operation, continuation, bulk$finishingJob);
    }

    @Override
    public void bulk$endTeardown() {
        bulk$delivery.endTeardown();
    }

    @Invoker(value = "finishJob", remap = false)
    protected abstract void bulk$invokeFinishJob(boolean success);

    @Invoker(value = "postChange", remap = false)
    protected abstract void bulk$invokePostChange(AEKey key);

    @Inject(
            method = "executeCrafting(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;Lnet/minecraft/world/level/Level;)I",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void bulk$executeCrafting(int maxPatterns, CraftingService craftingService,
            IEnergyService energyService, Level level, CallbackInfoReturnable<Integer> callback) {
        if (bulk$delivery.blocksReentry() || bulk$finishingJob) {
            callback.setReturnValue(0);
            return;
        }
        if (maxPatterns > 0 && job != null) {
            int consumed = BulkDispatchEngine.dispatch(new StandardAe2DispatchBridge(
                    this, craftingService, cpu::getGrid))
                    .schedulerOperations();
            if (consumed > 0) {
                callback.setReturnValue(consumed);
            }
        }
    }

    @Override
    @Nullable
    public IBulkJobBridge bulk$getJob() {
        return job == null ? null : (IBulkJobBridge) (Object) job;
    }

    @Override
    public ICraftingInventory bulk$getInventory() {
        return inventory;
    }

    @Override
    public void bulk$markDirty() {
        cpu.markDirty();
    }

    @Override
    public long bulk$simulateFinalDelivery(AEKey key, long amount) {
        var currentJob = bulk$requireFinalKeyAndAmount(key, amount);
        if (currentJob.bulk$isStandalone()) {
            bulk$preflightInventoryInsert(key, amount);
            return amount;
        }

        long requesterOffer = Math.min(amount, currentJob.bulk$getRemainingFinalAmount());
        bulk$preflightInventoryInsert(key, BulkLongMath.checkedSubtract(amount, requesterOffer));
        return currentJob.bulk$simulateRequesterDelivery(key, requesterOffer);
    }

    @Override
    public long bulk$deliverFinalFromEscrow(AEKey key, long amount) {
        if (bulk$finishingJob) {
            return 0;
        }
        return bulk$delivery.settle(() -> bulk$settleFinalDelivery(key, amount), this::cancel);
    }

    @Unique
    private long bulk$settleFinalDelivery(AEKey key, long amount) {
        var currentJob = bulk$requireFinalEscrowOffer(key, amount);
        if (amount == 0) {
            if (currentJob.bulk$getRemainingFinalAmount() == 0) {
                if (bulk$transferFinalSurplus(currentJob, key)) {
                    bulk$updateDisplayedOutput(currentJob);
                    cpu.markDirty();
                }
            }
            return 0;
        }

        long accepted;
        if (currentJob.bulk$isStandalone()) {
            long requestProgress = Math.min(amount, currentJob.bulk$getRemainingFinalAmount());
            long preflightAmount = requestProgress == currentJob.bulk$getRemainingFinalAmount()
                    ? currentJob.bulk$outputEscrow().get(key)
                    : amount;
            bulk$preflightInventoryInsert(key, preflightAmount);
            currentJob.bulk$outputEscrow().remove(key, amount);
            currentJob.bulk$decrementRemainingFinal(requestProgress);
            inventory.insert(key, amount, Actionable.MODULATE);
            accepted = amount;
        } else {
            long requesterOffer = Math.min(amount, currentJob.bulk$getRemainingFinalAmount());
            if (requesterOffer == currentJob.bulk$getRemainingFinalAmount()) {
                long surplus = BulkLongMath.checkedSubtract(
                        currentJob.bulk$outputEscrow().get(key), requesterOffer);
                bulk$preflightInventoryInsert(key, surplus);
            }
            accepted = BulkEscrowOperations.deliver(
                    currentJob.bulk$outputEscrow(),
                    key,
                    requesterOffer,
                    currentJob::bulk$deliverToRequester);
            currentJob.bulk$decrementRemainingFinal(accepted);
        }

        if (currentJob.bulk$getRemainingFinalAmount() == 0) {
            bulk$transferFinalSurplus(currentJob, key);
        }
        if (accepted > 0) {
            bulk$invokePostChange(key);
        }
        bulk$updateDisplayedOutput(currentJob);
        cpu.markDirty();
        return accepted;
    }

    @Override
    public void bulk$acceptIntermediateFromEscrow(AEKey key, long amount) {
        var currentJob = bulk$requireJob();
        if (currentJob.bulk$isFinalOutput(key)) {
            throw new IllegalArgumentException("final output cannot use the intermediate path");
        }
        if (amount < 0 || amount > currentJob.bulk$outputEscrow().get(key)) {
            throw new IllegalArgumentException("amount exceeds escrow ownership");
        }
        if (amount == 0) {
            return;
        }

        bulk$preflightInventoryInsert(key, amount);
        currentJob.bulk$outputEscrow().remove(key, amount);
        inventory.insert(key, amount, Actionable.MODULATE);
        cpu.markDirty();
    }

    @Override
    public boolean bulk$tryFinishJob() {
        if (bulk$delivery.blocksReentry() || bulk$finishingJob) {
            return false;
        }
        var currentJob = bulk$getJob();
        if (currentJob == null
                || currentJob.bulk$isCanceled()
                || !BulkEscrowOperations.canFinish(
                        currentJob.bulk$getRemainingFinalAmount(), currentJob.bulk$outputEscrow())) {
            return false;
        }

        bulk$invokeFinishJob(true);
        cpu.updateOutput(null);
        return true;
    }

    @Inject(method = "tickCraftingLogic", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$retryEscrowDelivery(CallbackInfo callback) {
        if (bulk$delivery.blocksReentry() || bulk$finishingJob) {
            callback.cancel();
            return;
        }
        var currentJob = bulk$getJob();
        if (currentJob == null
                || !cpu.isActive()
                || currentJob.bulk$isCanceled()
                || currentJob.bulk$outputEscrow().isEmpty()) {
            return;
        }

        bulk$beginCommit();
        boolean outcomeKnown = false;
        try {
            var entries = currentJob.bulk$outputEscrow().entries();
            for (var entry : entries) {
                if (!currentJob.bulk$isFinalOutput(entry.what())) {
                    bulk$acceptIntermediateFromEscrow(entry.what(), entry.amount());
                }
            }
            for (var entry : entries) {
                if (currentJob.bulk$isFinalOutput(entry.what())) {
                    bulk$deliverFinalFromEscrow(entry.what(), entry.amount());
                }
            }
            outcomeKnown = true;
        } finally {
            bulk$endCommit(outcomeKnown);
        }
        bulk$tryFinishJob();
    }

    @Inject(method = "cancel()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$deferReentrantCancel(CallbackInfo callback) {
        if (bulk$finishingJob || bulk$delivery.deferCancellation()) {
            callback.cancel();
        }
    }

    @Inject(method = "finishJob", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$guardCompletionAndTransferOnCancel(boolean success, CallbackInfo callback) {
        if (bulk$delivery.isQuarantined()) {
            // readFromNBT can cancel an invalid final output before returning.
            // Preserve the restored record without throwing out of owner load.
            callback.cancel();
            return;
        }
        if (bulk$finishingJob || bulk$delivery.blocksReentry()) {
            if (!success && !bulk$finishingJob) {
                bulk$delivery.deferCancellation();
            }
            callback.cancel();
            return;
        }
        var currentJob = bulk$getJob();
        if (currentJob == null) {
            callback.cancel();
            return;
        }
        if (currentJob.bulk$outputEscrow().isEmpty()) {
            bulk$finishingJob = true;
            return;
        }

        if (success) {
            bulk$updateDisplayedOutput(currentJob);
            cpu.markDirty();
            callback.cancel();
            return;
        }

        bulk$finishingJob = true;
        bulk$preflightEscrowTransfer(currentJob);
        BulkEscrowOperations.transferAll(
                currentJob.bulk$outputEscrow(),
                (key, amount) -> inventory.insert(key, amount, Actionable.MODULATE));
        cpu.markDirty();
    }

    @Inject(method = "finishJob", at = @At("TAIL"), remap = false)
    private void bulk$endJobFinish(boolean success, CallbackInfo callback) {
        bulk$finishingJob = false;
        bulk$delivery.drainTeardowns();
    }

    @Inject(method = "insert", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$rejectReentrantInsert(AEKey key, long amount, Actionable mode,
            CallbackInfoReturnable<Long> callback) {
        if (bulk$delivery.blocksReentry() || bulk$finishingJob) {
            callback.setReturnValue(0L);
        }
    }

    @Inject(method = "insert", at = @At("RETURN"), remap = false)
    private void bulk$restoreDisplayAfterRefusedCompletion(CallbackInfoReturnable<Long> callback) {
        var currentJob = bulk$getJob();
        if (currentJob != null
                && currentJob.bulk$getRemainingFinalAmount() == 0
                && !currentJob.bulk$outputEscrow().isEmpty()) {
            bulk$updateDisplayedOutput(currentJob);
        }
    }

    @Inject(method = "readFromNBT(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"), remap = false)
    private void bulk$readTransactionState(CompoundTag data, CallbackInfo callback) {
        bulk$delivery.readFromNBT(data);
    }

    @Inject(method = "readFromNBT", at = @At("TAIL"), remap = false)
    private void bulk$readJobEscrow(CompoundTag data, CallbackInfo callback) {
        var currentJob = bulk$getJob();
        if (currentJob != null && data.contains("job", Tag.TAG_COMPOUND)) {
            if (!bulk$delivery.isQuarantined() && !currentJob.bulk$outputEscrow().isEmpty()) {
                bulk$updateDisplayedOutput(currentJob);
            }
        }
    }

    @Inject(method = "writeToNBT", at = @At("TAIL"), remap = false)
    private void bulk$writeJobEscrow(CompoundTag data, CallbackInfo callback) {
        bulk$delivery.writeToNBT(data, bulk$finishingJob);
        var currentJob = bulk$getJob();
        if (currentJob != null && data.contains("job", Tag.TAG_COMPOUND)) {
            var jobData = data.getCompound("job");
            currentJob.bulk$writeOutputEscrow(jobData);
            data.put("job", jobData);
        }
    }

    @Inject(method = "getAllItems", at = @At("TAIL"), remap = false)
    private void bulk$includeEscrowInMonitor(KeyCounter output, CallbackInfo callback) {
        var currentJob = bulk$getJob();
        if (currentJob == null) {
            return;
        }
        for (var entry : currentJob.bulk$outputEscrow().entries()) {
            output.set(entry.what(), BulkLongMath.saturatedAddForDisplay(
                    output.get(entry.what()), entry.amount()));
        }
    }

    @Inject(method = "getStored", at = @At("RETURN"), cancellable = true, remap = false)
    private void bulk$includeEscrowInStored(AEKey key, CallbackInfoReturnable<Long> callback) {
        var currentJob = bulk$getJob();
        if (currentJob != null) {
            callback.setReturnValue(BulkLongMath.saturatedAddForDisplay(
                    callback.getReturnValue(), currentJob.bulk$outputEscrow().get(key)));
        }
    }

    @Unique
    private IBulkJobBridge bulk$requireJob() {
        var currentJob = bulk$getJob();
        if (currentJob == null) {
            throw new IllegalStateException("AdvancedAE CPU has no active crafting job");
        }
        return currentJob;
    }

    @Unique
    private IBulkJobBridge bulk$requireFinalKeyAndAmount(AEKey key, long amount) {
        var currentJob = bulk$requireJob();
        if (!currentJob.bulk$isFinalOutput(key)) {
            throw new IllegalArgumentException("key is not the job final output");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        return currentJob;
    }

    @Unique
    private IBulkJobBridge bulk$requireFinalEscrowOffer(AEKey key, long amount) {
        var currentJob = bulk$requireFinalKeyAndAmount(key, amount);
        if (amount > currentJob.bulk$outputEscrow().get(key)) {
            throw new IllegalArgumentException("amount exceeds escrow ownership");
        }
        return currentJob;
    }

    @Unique
    private void bulk$preflightInventoryInsert(AEKey key, long amount) {
        long stored = inventory.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
        BulkLongMath.checkedAdd(stored, amount);
    }

    @Unique
    private void bulk$preflightEscrowTransfer(IBulkJobBridge currentJob) {
        for (var entry : currentJob.bulk$outputEscrow().entries()) {
            bulk$preflightInventoryInsert(entry.what(), entry.amount());
        }
    }

    @Unique
    private boolean bulk$transferFinalSurplus(IBulkJobBridge currentJob, AEKey key) {
        long surplus = currentJob.bulk$outputEscrow().get(key);
        if (surplus == 0) {
            return false;
        }
        bulk$preflightInventoryInsert(key, surplus);
        currentJob.bulk$outputEscrow().remove(key, surplus);
        inventory.insert(key, surplus, Actionable.MODULATE);
        return true;
    }

    @Unique
    private void bulk$updateDisplayedOutput(IBulkJobBridge currentJob) {
        var finalOutputKey = currentJob.bulk$getFinalOutputKey();
        long displayedAmount = currentJob.bulk$getRemainingFinalAmount();
        if (displayedAmount == 0) {
            displayedAmount = currentJob.bulk$outputEscrow().get(finalOutputKey);
            if (displayedAmount == 0 && !currentJob.bulk$outputEscrow().isEmpty()) {
                displayedAmount = 1;
            }
        }
        cpu.updateOutput(new GenericStack(finalOutputKey, displayedAmount));
    }
}
