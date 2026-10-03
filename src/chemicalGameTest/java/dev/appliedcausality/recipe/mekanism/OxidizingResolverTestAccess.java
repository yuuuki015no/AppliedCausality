package dev.appliedcausality.recipe.mekanism;

import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ResolvedProcess;
import net.minecraft.world.item.crafting.Recipe;

import java.util.Optional;

/** Conditional GameTest bridge to the dormant package-private resolver. */
public final class OxidizingResolverTestAccess {
    private OxidizingResolverTestAccess() {
    }

    public static Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
        return MekanismItemToChemicalRecipeResolver.createOxidizing().resolve(recipe, context);
    }
}
