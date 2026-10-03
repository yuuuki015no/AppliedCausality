package dev.appliedcausality.internal.bulk;

import java.util.Objects;

/** Exact outcome of one fake/common Bulk dispatch attempt. */
public record BulkDispatchResult(
        Status status,
        long committedMultiplier,
        long finalOutputAccepted,
        int schedulerOperations,
        boolean jobCompleted,
        BulkDispatchMetrics metrics) {
    public BulkDispatchResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(metrics, "metrics");
        if (committedMultiplier < 0 || finalOutputAccepted < 0) {
            throw new IllegalArgumentException("committed quantities must be non-negative");
        }
        if (schedulerOperations < 0 || schedulerOperations > 1) {
            throw new IllegalArgumentException("a dispatch may consume at most one scheduler operation");
        }
        if (status == Status.COMMITTED) {
            if (committedMultiplier == 0 || schedulerOperations != 1) {
                throw new IllegalArgumentException("a committed dispatch needs positive work and one operation");
            }
        } else if (committedMultiplier != 0
                || finalOutputAccepted != 0
                || schedulerOperations != 0
                || jobCompleted) {
            throw new IllegalArgumentException("an uncommitted result must not report committed work");
        }
    }

    public boolean committed() {
        return status == Status.COMMITTED;
    }

    public enum Status {
        COMMITTED,
        NO_JOB,
        JOB_CANCELED,
        NO_VERIFIED_PROCESS,
        NO_CAPACITY,
        PREFLIGHT_CHANGED,
        COMMIT_REJECTED
    }
}
