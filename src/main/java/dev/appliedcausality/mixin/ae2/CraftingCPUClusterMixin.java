package dev.appliedcausality.mixin.ae2;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import dev.appliedcausality.internal.cpu.IBulkCpuLifecycle;

@Mixin(value = CraftingCPUCluster.class, remap = false)
public abstract class CraftingCPUClusterMixin {
    @Shadow(remap = false)
    @Final
    public CraftingCpuLogic craftingLogic;
    @Shadow(remap = false)
    private boolean isDestroyed;
    @Shadow(remap = false)
    public abstract void destroy();
    @Unique
    private final Object bulk$destroyOperation = new Object();

    @Inject(method = "destroy()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulk$beginDestroy(CallbackInfo callback) {
        // Stock's early return does not reach TAIL and must not acquire depth.
        if (!isDestroyed && !((IBulkCpuLifecycle) (Object) craftingLogic)
                .bulk$beginTeardown(bulk$destroyOperation, this::destroy)) {
            callback.cancel();
        }
    }

    @Inject(method = "destroy()V", at = @At("TAIL"), remap = false)
    private void bulk$endDestroy(CallbackInfo callback) {
        ((IBulkCpuLifecycle) (Object) craftingLogic).bulk$endTeardown();
    }
}
