package dev.appliedcausality.mixin.advancedae;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.appliedcausality.internal.cpu.IAdvancedAeCpuOwnerBridge;
import dev.appliedcausality.internal.cpu.IAdvancedAeLifecycleBridge;
import dev.appliedcausality.internal.cpu.IBulkCpuLifecycle;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCluster;
import net.pedroksl.advanced_ae.common.entities.AdvCraftingBlockEntity;

/** Keeps AdvancedAE's cancel/drop/cluster replacement body outside Bulk commit callbacks. */
@Mixin(value = AdvCraftingBlockEntity.class, remap = false)
public abstract class AdvCraftingBlockEntityMixin implements IAdvancedAeLifecycleBridge {
    @Shadow(remap = false)
    private AdvCraftingCPUCluster cluster;

    @Shadow(remap = false)
    public abstract void breakCluster();

    @Shadow(remap = false)
    public abstract void updateStatus(AdvCraftingCPUCluster replacement);

    @Unique
    private final Object bulk$breakOperation = new Object();

    @Unique
    private final Object bulk$statusOperation = new Object();

    @Unique
    private final Deque<List<IBulkCpuLifecycle>> bulk$lifecycleStack = new ArrayDeque<>();

    @Inject(method = "breakCluster()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$beginBreak(CallbackInfo callback) {
        var original = cluster;
        bulk$beginLifecycle(bulk$breakOperation, () -> {
            if (cluster == original) {
                breakCluster();
            }
        }, callback);
    }

    @Inject(
            method = "updateStatus(Lnet/pedroksl/advanced_ae/common/cluster/AdvCraftingCPUCluster;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void bulk$beginStatus(AdvCraftingCPUCluster replacement, CallbackInfo callback) {
        var original = cluster;
        bulk$beginLifecycle(bulk$statusOperation, () -> {
            if (cluster == original) {
                updateStatus(replacement);
            }
        }, callback);
    }

    @Unique
    private void bulk$beginLifecycle(Object operation, Runnable continuation, CallbackInfo callback) {
        if (cluster == null) {
            bulk$lifecycleStack.push(List.of());
            return;
        }

        var ownedCpus = ((IAdvancedAeCpuOwnerBridge) (Object) cluster)
                .bulk$snapshotOwnedCpuLifecycles();
        var acquired = new ArrayList<IBulkCpuLifecycle>(ownedCpus.size());
        for (var lifecycle : ownedCpus) {
            if (!lifecycle.bulk$beginTeardown(operation, continuation)) {
                bulk$release(acquired);
                callback.cancel();
                return;
            }
            acquired.add(lifecycle);
        }
        bulk$lifecycleStack.push(acquired);
    }

    @Inject(
            method = {
                "breakCluster()V",
                "updateStatus(Lnet/pedroksl/advanced_ae/common/cluster/AdvCraftingCPUCluster;)V"
            },
            at = @At("TAIL"),
            remap = false)
    private void bulk$endLifecycle(CallbackInfo callback) {
        bulk$release(bulk$lifecycleStack.pop());
    }

    @Unique
    private static void bulk$release(List<IBulkCpuLifecycle> acquired) {
        for (int index = acquired.size() - 1; index >= 0; index--) {
            acquired.get(index).bulk$endTeardown();
        }
    }
}
