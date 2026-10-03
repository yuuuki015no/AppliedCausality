package dev.appliedcausality.internal.cpu;

import java.util.List;

/** Side-effect-free view of every CPU whose inventory is still owned by an AdvancedAE cluster. */
public interface IAdvancedAeCpuOwnerBridge {
    List<IBulkCpuLifecycle> bulk$snapshotOwnedCpuLifecycles();
}
