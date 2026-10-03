package dev.appliedcausality.gametest;

import java.lang.management.ManagementFactory;

import dev.appliedcausality.internal.bulk.BulkExecutionMetrics;

/** Test-only wall/CPU/allocation probe around one already-prepared E2E dispatch. */
final class Phase21PerformanceProbe {
    private Phase21PerformanceProbe() {
    }

    static Measurement measure(Runnable dispatch) {
        var threadBean = ManagementFactory.getThreadMXBean();
        if (threadBean.isCurrentThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            threadBean.setThreadCpuTimeEnabled(true);
        }
        com.sun.management.ThreadMXBean allocationBean = allocationBean();
        long threadId = Thread.currentThread().getId();
        long allocatedBefore = allocationBean == null
                ? -1L
                : allocationBean.getThreadAllocatedBytes(threadId);
        long cpuBefore = threadBean.isCurrentThreadCpuTimeSupported()
                ? threadBean.getCurrentThreadCpuTime()
                : -1L;
        long wallBefore = System.nanoTime();
        BulkExecutionMetrics.Snapshot metrics;
        try (var recording = BulkExecutionMetrics.begin()) {
            dispatch.run();
            metrics = recording.snapshot();
        }
        long wallNanos = System.nanoTime() - wallBefore;
        long cpuNanos = cpuBefore < 0
                ? -1L
                : threadBean.getCurrentThreadCpuTime() - cpuBefore;
        long allocatedBytes = allocatedBefore < 0
                ? -1L
                : allocationBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        return new Measurement(wallNanos, cpuNanos, allocatedBytes, metrics);
    }

    record PreparedInvocation(
            String integration,
            long multiplier,
            long craftingStorageBytes,
            Runnable dispatch,
            Runnable verify) {
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
            allocationBean.setThreadAllocatedMemoryEnabled(true);
        }
        return allocationBean;
    }

    record Measurement(
            long wallNanos,
            long cpuNanos,
            long allocatedBytes,
            BulkExecutionMetrics.Snapshot metrics) {
    }
}
