package dev.appliedcausality.internal.cpu;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import dev.appliedcausality.mixin.ae2.ElapsedTimeTrackerAccessor;
import dev.appliedcausality.mixin.ae2.TaskProgressAccessor;

/**
 * Fails startup immediately when the exact-version AE2 bridge Mixins did not
 * apply to their intended targets.
 */
public final class Ae2BridgeRuntimeVerifier {
    private Ae2BridgeRuntimeVerifier() {
    }

    public static void verify() {
        requireBridge(CraftingCpuLogic.class, IBulkCpuBridge.class);
        requireBridge(ExecutingCraftingJob.class, IBulkJobBridge.class);
        requireBridge(ElapsedTimeTracker.class, ElapsedTimeTrackerAccessor.class);
        try {
            requireBridge(
                    Class.forName("appeng.crafting.execution.ExecutingCraftingJob$TaskProgress"),
                    TaskProgressAccessor.class);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("AE2 TaskProgress class is unavailable", exception);
        }
    }

    private static void requireBridge(Class<?> target, Class<?> bridge) {
        if (!bridge.isAssignableFrom(target)) {
            throw new IllegalStateException(
                    bridge.getSimpleName() + " was not applied to " + target.getName());
        }
    }
}
