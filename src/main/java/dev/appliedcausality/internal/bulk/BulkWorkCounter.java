package dev.appliedcausality.internal.bulk;

/** Mutable counter kept package-private so instrumentation cannot become API. */
final class BulkWorkCounter {
    private long taskIterations;
    private long providerIterations;
    private long snapshotKeyIterations;
    private long plannerKeyIterations;
    private long commitKeyIterations;
    private long commitStageIterations;

    void task() {
        taskIterations = increment(taskIterations);
    }

    void provider() {
        providerIterations = increment(providerIterations);
    }

    void snapshotKey() {
        snapshotKeyIterations = increment(snapshotKeyIterations);
    }

    void plannerKey() {
        plannerKeyIterations = increment(plannerKeyIterations);
    }

    void commitKey() {
        commitKeyIterations = increment(commitKeyIterations);
    }

    void commitStage() {
        commitStageIterations = increment(commitStageIterations);
    }

    BulkDispatchMetrics snapshot() {
        return new BulkDispatchMetrics(
                taskIterations,
                providerIterations,
                snapshotKeyIterations,
                plannerKeyIterations,
                commitKeyIterations,
                commitStageIterations);
    }

    private static long increment(long value) {
        return Math.addExact(value, 1L);
    }
}
