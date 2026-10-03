package dev.appliedcausality.internal.cost;

/**
 * Commit-time handle created by a side-effect-free cost preflight.
 *
 * <p>{@link #tryConsume()} must either consume the complete planned cost or
 * leave its source unchanged. If a later input precondition rejects the Bulk
 * commit, {@link #restore()} returns a successfully consumed cost before the
 * engine reports that rejection.</p>
 */
public interface BulkCostCommitment {
    /**
     * Consumes the exact preflighted cost. A {@code false} result must not
     * mutate the cost source.
     */
    boolean tryConsume();

    /** Restores a cost previously consumed by {@link #tryConsume()}. */
    void restore();
}
