package dev.appliedcausality.internal.bulk;

/**
 * Instrumented loop/step counts for one Bulk dispatch attempt.
 *
 * <p>These counters measure work at task, provider, distinct-key, and commit
 * stage boundaries. They never count units represented by the multiplier.</p>
 */
public record BulkDispatchMetrics(
        long taskIterations,
        long providerIterations,
        long snapshotKeyIterations,
        long plannerKeyIterations,
        long commitKeyIterations,
        long commitStageIterations) {
    public BulkDispatchMetrics {
        requireNonNegative(taskIterations, "taskIterations");
        requireNonNegative(providerIterations, "providerIterations");
        requireNonNegative(snapshotKeyIterations, "snapshotKeyIterations");
        requireNonNegative(plannerKeyIterations, "plannerKeyIterations");
        requireNonNegative(commitKeyIterations, "commitKeyIterations");
        requireNonNegative(commitStageIterations, "commitStageIterations");
    }

    public long measuredIterations() {
        long result = BulkLongMath.checkedAdd(taskIterations, providerIterations);
        result = BulkLongMath.checkedAdd(result, snapshotKeyIterations);
        result = BulkLongMath.checkedAdd(result, plannerKeyIterations);
        result = BulkLongMath.checkedAdd(result, commitKeyIterations);
        return BulkLongMath.checkedAdd(result, commitStageIterations);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
