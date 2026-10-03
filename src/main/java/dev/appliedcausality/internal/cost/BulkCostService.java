package dev.appliedcausality.internal.cost;

import java.util.Objects;

import dev.appliedcausality.internal.validation.VerifiedProcessModel;

/**
 * Isolated dispatch-cost service. v0.1 always uses the fixed {@code NONE}
 * policy; callers cannot configure it through recipe or adapter data.
 */
public final class BulkCostService {
    private static final BulkCostService V0_1_NONE = new BulkCostService(
            context -> BulkCostPreflight.none());

    private final BulkCostPolicy policy;

    public BulkCostService(BulkCostPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public static BulkCostService v0_1None() {
        return V0_1_NONE;
    }

    public BulkCostPreflight preflight(VerifiedProcessModel process, long requestedMultiplier) {
        var context = new BulkCostPlanningContext(process, requestedMultiplier);
        return Objects.requireNonNull(policy.preflight(context), "cost preflight");
    }
}
