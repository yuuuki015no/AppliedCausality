package dev.appliedcausality.api.process;

import appeng.api.crafting.IPatternDetails;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import java.util.Objects;

/**
 * Server-owned context supplied while an adapter resolves a recipe for a
 * processing pattern.
 *
 * <p>The pattern is contextual input for interpreting recipes with concrete
 * input-dependent semantics. Final pattern agreement remains the responsibility
 * of Applied Causality's internal validator.</p>
 */
public record PatternContext(Level level, IPatternDetails pattern) {
    public PatternContext {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pattern, "pattern");
    }

    public RegistryAccess registryAccess() {
        return level.registryAccess();
    }

    public RecipeManager recipeManager() {
        return level.getRecipeManager();
    }
}
