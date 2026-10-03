package dev.appliedcausality.internal.cost;

import java.util.Objects;

/**
 * Immutable result of cost availability preflight. The multiplier limit is
 * folded into the immutable Bulk commit plan before any input extraction.
 */
public record BulkCostPreflight(long multiplierLimit, BulkCostCommitmentFactory commitmentFactory) {
    private static final BulkCostCommitment NONE_COMMITMENT = new BulkCostCommitment() {
        @Override
        public boolean tryConsume() {
            return true;
        }

        @Override
        public void restore() {
            // CostPolicy.NONE has no mutable source.
        }
    };

    private static final BulkCostPreflight NONE = new BulkCostPreflight(
            Long.MAX_VALUE, multiplier -> NONE_COMMITMENT);

    public BulkCostPreflight {
        if (multiplierLimit < 0) {
            throw new IllegalArgumentException("multiplierLimit must be non-negative");
        }
        Objects.requireNonNull(commitmentFactory, "commitmentFactory");
    }

    public static BulkCostPreflight none() {
        return NONE;
    }

    public BulkCostCommitment commitmentFor(long multiplier) {
        if (multiplier <= 0 || multiplier > multiplierLimit) {
            throw new IllegalArgumentException("multiplier is outside the cost preflight limit");
        }
        return Objects.requireNonNull(commitmentFactory.create(multiplier), "cost commitment");
    }
}
