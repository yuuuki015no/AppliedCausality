package dev.appliedcausality.api.process;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.Collection;
import java.util.Optional;

/**
 * Translates recipes owned by a mod into unverified, normalized process candidates.
 *
 * <p>Returning a {@link ResolvedProcess} does not authorize execution. Applied
 * Causality's internal validator must compare the candidate with the processing
 * pattern and promote it before any Bulk execution can occur.</p>
 */
public interface IBulkRecipeAdapter {
    /**
     * Stable, namespaced identity of this adapter.
     */
    ResourceLocation id();

    /**
     * Selection priority. Higher values sort before lower values.
     */
    int priority();

    /**
     * Recipe types this adapter can resolve.
     *
     * <p>The registry snapshots this collection during registration and uses it
     * to build its {@code RecipeType -> adapters} index.</p>
     */
    Collection<RecipeType<?>> supportedRecipeTypes();

    /**
     * Resolves one recipe into an unverified process candidate.
     */
    Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context);
}
