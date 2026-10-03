package dev.appliedcausality.mixin.advancedae;

import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.inv.ListCraftingInventory;
import dev.appliedcausality.internal.bulk.BulkLongMath;
import dev.appliedcausality.internal.cpu.AdvancedAeTaskView;
import dev.appliedcausality.internal.cpu.BulkJobEscrowPersistence;
import dev.appliedcausality.internal.cpu.BulkOutputEscrow;
import dev.appliedcausality.internal.cpu.IBulkJobBridge;
import dev.appliedcausality.internal.cpu.IBulkOutputEscrow;
import dev.appliedcausality.internal.cpu.IBulkTaskView;
import net.minecraft.nbt.CompoundTag;
import net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker;
import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;

@Mixin(value = ExecutingCraftingJob.class, remap = false)
public abstract class ExecutingCraftingJobMixin implements IBulkJobBridge {
    @Shadow(remap = false)
    @Final
    private CraftingLink link;

    @Shadow(remap = false)
    @Final
    private ListCraftingInventory waitingFor;

    @Shadow(remap = false)
    @Final
    private Map<IPatternDetails, ?> tasks;

    @Shadow(remap = false)
    @Final
    private ElapsedTimeTracker timeTracker;

    @Shadow(remap = false)
    private GenericStack finalOutput;

    @Shadow(remap = false)
    private long remainingAmount;

    @Unique
    private BulkOutputEscrow bulk$outputEscrow = new BulkOutputEscrow();

    @Inject(
            method = "<init>(Lnet/minecraft/nbt/CompoundTag;Lnet/pedroksl/advanced_ae/common/logic/ExecutingCraftingJob$CraftingDifferenceListener;Lnet/pedroksl/advanced_ae/common/logic/AdvCraftingCPULogic;)V",
            at = @At("RETURN"), remap = false)
    private void bulk$restoreEscrowBeforeCpuLifecycle(CompoundTag data,
            @Coerce Object listener, AdvCraftingCPULogic cpu, CallbackInfo callback) {
        bulk$readOutputEscrow(data);
    }

    @Override
    public Iterable<IBulkTaskView> bulk$tasks() {
        var result = new ArrayList<IBulkTaskView>(tasks.size());
        for (var entry : tasks.entrySet()) {
            result.add(new AdvancedAeTaskView(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    @Override
    public void bulk$cleanCompletedTasks() {
        tasks.entrySet().removeIf(
                entry -> new AdvancedAeTaskView(entry.getKey(), entry.getValue()).remaining() == 0);
    }

    @Override
    public long bulk$getWaitingFor(AEKey key) {
        return waitingFor.extract(Objects.requireNonNull(key, "key"), Long.MAX_VALUE, Actionable.SIMULATE);
    }

    @Override
    public void bulk$addWaitingFor(AEKey key, long amount) {
        Objects.requireNonNull(key, "key");
        requireNonNegative(amount, "amount");
        if (amount == 0) {
            return;
        }
        BulkLongMath.checkedAdd(bulk$getWaitingFor(key), amount);
        waitingFor.insert(key, amount, Actionable.MODULATE);
    }

    @Override
    public void bulk$materializeWaitingToEscrow(AEKey key, long amount) {
        Objects.requireNonNull(key, "key");
        requireNonNegative(amount, "amount");
        if (amount == 0) {
            return;
        }
        if (amount > bulk$getWaitingFor(key)) {
            throw new IllegalArgumentException("amount exceeds waiting ownership");
        }

        BulkLongMath.checkedAdd(bulk$outputEscrow.get(key), amount);
        bulk$outputEscrow.add(key, amount);
        ((ElapsedTimeTrackerAccessor) (Object) timeTracker).bulk$decrementItems(amount, key.getType());
        long extracted = waitingFor.extract(key, amount, Actionable.MODULATE);
        if (extracted != amount) {
            throw new IllegalStateException("AdvancedAE waiting state changed during materialization");
        }
    }

    @Override
    public IBulkOutputEscrow bulk$outputEscrow() {
        return bulk$outputEscrow;
    }

    @Override
    public boolean bulk$isFinalOutput(AEKey key) {
        return Objects.requireNonNull(key, "key").matches(finalOutput);
    }

    @Override
    public AEKey bulk$getFinalOutputKey() {
        if (finalOutput == null) {
            throw new IllegalStateException("AdvancedAE job has no final output");
        }
        return finalOutput.what();
    }

    @Override
    public boolean bulk$isStandalone() {
        return link.isStandalone();
    }

    @Override
    public boolean bulk$isCanceled() {
        return link.isCanceled();
    }

    @Override
    public long bulk$getRemainingFinalAmount() {
        return remainingAmount;
    }

    @Override
    public void bulk$decrementRemainingFinal(long amount) {
        remainingAmount = BulkLongMath.checkedSubtract(remainingAmount, amount);
    }

    @Override
    public long bulk$simulateRequesterDelivery(AEKey key, long amount) {
        return bulk$requesterDelivery(key, amount, Actionable.SIMULATE);
    }

    @Override
    public long bulk$deliverToRequester(AEKey key, long amount) {
        return bulk$requesterDelivery(key, amount, Actionable.MODULATE);
    }

    @Override
    public void bulk$readOutputEscrow(CompoundTag jobData) {
        bulk$outputEscrow = BulkJobEscrowPersistence.read(jobData);
    }

    @Override
    public void bulk$writeOutputEscrow(CompoundTag jobData) {
        BulkJobEscrowPersistence.write(jobData, bulk$outputEscrow);
    }

    @Unique
    private long bulk$requesterDelivery(AEKey key, long amount, Actionable mode) {
        Objects.requireNonNull(key, "key");
        requireNonNegative(amount, "amount");
        if (!bulk$isFinalOutput(key)) {
            throw new IllegalArgumentException("key is not the job final output");
        }
        long accepted = link.insert(key, amount, mode);
        if (accepted < 0 || accepted > amount) {
            throw new IllegalStateException(
                    "AdvancedAE requester returned an invalid accepted amount: " + accepted);
        }
        return accepted;
    }

    @Unique
    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
