package dev.appliedcausality.mixin.advancedae;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.appliedcausality.internal.cpu.IAdvancedAeCpuOwnerBridge;
import dev.appliedcausality.internal.cpu.IAdvancedAeLifecycleBridge;
import dev.appliedcausality.internal.cpu.IBulkCpuLifecycle;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCluster;

/** Defers destruction of the whole multi-job cluster while any CPU owns an in-flight commit. */
@Mixin(value = AdvCraftingCPUCluster.class, remap = false)
public abstract class AdvCraftingCPUClusterMixin
        implements IAdvancedAeLifecycleBridge, IAdvancedAeCpuOwnerBridge {
    @Shadow(remap = false)
    @Final
    private HashMap<UUID, AdvCraftingCPU> activeCpus;

    @Shadow(remap = false)
    private boolean isDestroyed;

    @Shadow(remap = false)
    public abstract void destroy();

    @Unique
    private final Object bulk$destroyOperation = new Object();

    @Unique
    private final Deque<List<IBulkCpuLifecycle>> bulk$destroyStack = new ArrayDeque<>();

    @Inject(method = "killCpu(Ljava/util/UUID;Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$retainQuarantinedCpu(UUID id, boolean force, CallbackInfo callback) {
        var ownedCpu = activeCpus.get(id);
        if (ownedCpu != null
                && ((IBulkCpuLifecycle) (Object) ownedCpu.craftingLogic).bulk$isQuarantined()) {
            // Stock enumeration may collect job-less CPUs. Keep ambiguous
            // inventory in the actual persistence map without pretending it has a job.
            callback.cancel();
        }
    }

    @Override
    public List<IBulkCpuLifecycle> bulk$snapshotOwnedCpuLifecycles() {
        var result = new ArrayList<IBulkCpuLifecycle>(activeCpus.size());
        for (var ownedCpu : activeCpus.values()) {
            result.add((IBulkCpuLifecycle) (Object) ownedCpu.craftingLogic);
        }
        return result;
    }

    @Inject(method = "destroy()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$beginDestroy(CallbackInfo callback) {
        if (isDestroyed) {
            return;
        }

        var ownedCpus = bulk$snapshotOwnedCpuLifecycles();
        var acquired = new ArrayList<IBulkCpuLifecycle>(ownedCpus.size());
        for (var lifecycle : ownedCpus) {
            if (!lifecycle.bulk$beginTeardown(bulk$destroyOperation, this::destroy)) {
                bulk$release(acquired);
                callback.cancel();
                return;
            }
            acquired.add(lifecycle);
        }
        bulk$destroyStack.push(acquired);
    }

    @Inject(method = "destroy()V", at = @At("TAIL"), remap = false)
    private void bulk$endDestroy(CallbackInfo callback) {
        bulk$release(bulk$destroyStack.pop());
    }

    @Unique
    private static void bulk$release(List<IBulkCpuLifecycle> acquired) {
        for (int index = acquired.size() - 1; index >= 0; index--) {
            acquired.get(index).bulk$endTeardown();
        }
    }
}
