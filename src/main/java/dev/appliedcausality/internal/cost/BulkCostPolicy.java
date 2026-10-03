package dev.appliedcausality.internal.cost;

/**
 * Internal policy boundary for dispatch cost. Implementations must perform no
 * mutation in {@link #preflight(BulkCostPlanningContext)}.
 */
@FunctionalInterface
public interface BulkCostPolicy {
    BulkCostPreflight preflight(BulkCostPlanningContext context);
}
