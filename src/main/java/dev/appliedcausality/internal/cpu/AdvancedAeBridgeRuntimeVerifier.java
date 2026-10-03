package dev.appliedcausality.internal.cpu;

import dev.appliedcausality.internal.compat.AdvancedAeCompatibility;

/** Verifies the optional exact-version bridge after Mixins have transformed its targets. */
public final class AdvancedAeBridgeRuntimeVerifier {
    private static final String LOGIC =
            "net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic";
    private static final String JOB =
            "net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob";
    private static final String ELAPSED =
            "net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker";
    private static final String TASK = JOB + "$TaskProgress";
    private static final String CLUSTER =
            "net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCluster";
    private static final String BLOCK_ENTITY =
            "net.pedroksl.advanced_ae.common.entities.AdvCraftingBlockEntity";

    private AdvancedAeBridgeRuntimeVerifier() {
    }

    public static void verifyIfLoaded() {
        if (!AdvancedAeCompatibility.isLoadedAndCompatible()) {
            return;
        }

        try {
            requireBridge(Class.forName(LOGIC), IBulkCpuBridge.class);
            requireBridge(Class.forName(JOB), IBulkJobBridge.class);
            requireBridge(
                    Class.forName(ELAPSED),
                    Class.forName("dev.appliedcausality.mixin.advancedae.ElapsedTimeTrackerAccessor"));
            requireBridge(
                    Class.forName(TASK),
                    Class.forName("dev.appliedcausality.mixin.advancedae.TaskProgressAccessor"));
            requireBridge(Class.forName(CLUSTER), IAdvancedAeLifecycleBridge.class);
            requireBridge(Class.forName(CLUSTER), IAdvancedAeCpuOwnerBridge.class);
            requireBridge(Class.forName(BLOCK_ENTITY), IAdvancedAeLifecycleBridge.class);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(
                    "AdvancedAE 1.3.5 compatibility target is unavailable", exception);
        }
    }

    private static void requireBridge(Class<?> target, Class<?> bridge) {
        if (!bridge.isAssignableFrom(target)) {
            throw new IllegalStateException(
                    bridge.getSimpleName() + " was not applied to " + target.getName());
        }
    }
}
