package dev.appliedcausality.internal.cost;

import java.util.Objects;

import dev.appliedcausality.internal.validation.VerifiedProcessModel;

/** Immutable, side-effect-free input to a Bulk cost-policy preflight. */
public record BulkCostPlanningContext(VerifiedProcessModel process, long requestedMultiplier) {
    public BulkCostPlanningContext {
        Objects.requireNonNull(process, "process");
        if (requestedMultiplier <= 0) {
            throw new IllegalArgumentException("requestedMultiplier must be positive");
        }
    }
}
