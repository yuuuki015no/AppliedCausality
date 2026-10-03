package dev.appliedcausality.internal.cost;

/** Creates an exact commit-time cost handle for an approved multiplier. */
@FunctionalInterface
public interface BulkCostCommitmentFactory {
    BulkCostCommitment create(long multiplier);
}
