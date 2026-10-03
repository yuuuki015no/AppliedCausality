package dev.appliedcausality.internal.provider;

import appeng.api.crafting.IPatternDetails;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;

import java.util.Optional;

/**
 * Internal trust-preserving provider boundary used by later Bulk CPU bridges.
 * Implementations may expose only models produced by the Core validator.
 */
public interface IBulkCraftingProvider {
    Optional<VerifiedProcessModel> getVerifiedProcessModel(IPatternDetails pattern);

    long getMaxBulkOperations();
}
