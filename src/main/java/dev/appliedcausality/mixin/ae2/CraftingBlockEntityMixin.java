package dev.appliedcausality.mixin.ae2;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import dev.appliedcausality.internal.cpu.IBulkCpuLifecycle;

@Mixin(value = CraftingBlockEntity.class, remap = false)
public abstract class CraftingBlockEntityMixin {
    @Shadow(remap = false)
    private CraftingCPUCluster cluster;

    @Shadow(remap = false)
    public abstract void breakCluster();

    @Shadow(remap = false)
    public abstract void updateStatus(CraftingCPUCluster replacement);

    @Unique
    private final Object bulk$breakOperation = new Object();
    @Unique
    private final Object bulk$statusOperation = new Object();
    // Stock destruction recurses through updateStatus -> breakCluster. Retain
    // each original CPU even when the body clears or replaces this.cluster.
    @Unique
    private final List<IBulkCpuLifecycle> bulk$lifecycleStack = new ArrayList<>();

    @Inject(method = "breakCluster()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$beginBreak(CallbackInfo callback) {
        var original = cluster;
        bulk$beginLifecycle(bulk$breakOperation, () -> {
            // A prior queued updateStatus may already have dismantled the old
            // cluster and installed another one. This request belongs to the old CPU.
            if (cluster == original) {
                breakCluster();
            }
        }, callback);
    }

    @Inject(method = "updateStatus(Lappeng/me/cluster/implementations/CraftingCPUCluster;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$beginStatus(CraftingCPUCluster replacement, CallbackInfo callback) {
        bulk$beginLifecycle(bulk$statusOperation, () -> updateStatus(replacement), callback);
    }

    @Unique
    private void bulk$beginLifecycle(Object operation, Runnable continuation, CallbackInfo callback) {
        var lifecycle = cluster == null ? null : (IBulkCpuLifecycle) (Object) cluster.craftingLogic;
        if (lifecycle != null && !lifecycle.bulk$beginTeardown(operation, continuation)) {
            callback.cancel();
            return;
        }
        bulk$lifecycleStack.add(lifecycle);
    }

    @Inject(method = { "breakCluster()V", "updateStatus(Lappeng/me/cluster/implementations/CraftingCPUCluster;)V" },
            at = @At("TAIL"), remap = false)
    private void bulk$endLifecycle(CallbackInfo callback) {
        var lifecycle = bulk$lifecycleStack.remove(bulk$lifecycleStack.size() - 1);
        if (lifecycle != null) {
            lifecycle.bulk$endTeardown();
        }
    }
}
