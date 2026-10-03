package dev.appliedcausality.recipe.mekanism;

import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ResolvedProcess;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.Optional;

/** One exact Mekanism recipe family behind the canonical adapter identity. */
interface MekanismRecipeFamilyResolver {
    RecipeType<?> supportedRecipeType();

    Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context);
}
