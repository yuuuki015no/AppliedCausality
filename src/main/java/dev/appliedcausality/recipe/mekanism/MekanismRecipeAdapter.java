package dev.appliedcausality.recipe.mekanism;

import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.IBulkRecipeAdapter;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ResolvedProcess;
import dev.appliedcausality.internal.compat.AppliedMekanisticsCompatibility;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One registered Mekanism identity with exact descriptors for each supported type. */
public final class MekanismRecipeAdapter implements IBulkRecipeAdapter {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            AppliedCausality.MOD_ID, "mekanism");

    private final Map<RecipeType<?>, MekanismRecipeFamilyResolver> descriptors;
    private final List<RecipeType<?>> types;

    MekanismRecipeAdapter(List<? extends MekanismRecipeFamilyResolver> resolvers) {
        Map<RecipeType<?>, MekanismRecipeFamilyResolver> byType = new IdentityHashMap<>();
        for (MekanismRecipeFamilyResolver resolver : resolvers) {
            RecipeType<?> type = resolver.supportedRecipeType();
            if (byType.put(type, resolver) != null) {
                throw new IllegalArgumentException("Duplicate Mekanism recipe type: " + type);
            }
        }
        descriptors = Collections.unmodifiableMap(byType);
        List<RecipeType<?>> declaredTypes = new ArrayList<>(resolvers.size());
        for (MekanismRecipeFamilyResolver resolver : resolvers) {
            declaredTypes.add(resolver.supportedRecipeType());
        }
        types = List.copyOf(declaredTypes);
    }

    public static MekanismRecipeAdapter createBundled() {
        List<MekanismRecipeFamilyResolver> resolvers = new ArrayList<>(
                MekanismItemToItemRecipeAdapter.createBundledResolvers());
        resolvers.add(MekanismCombiningRecipeResolver.createBundled());
        resolvers.add(MekanismDeterministicSawingResolver.createBundled());
        if (AppliedMekanisticsCompatibility.isLoadedAndCompatible()) {
            resolvers.add(MekanismItemToChemicalRecipeResolver.createOxidizing());
            resolvers.add(MekanismItemToChemicalRecipeResolver.createGasConversion());
            resolvers.add(MekanismItemToChemicalRecipeResolver.createInfusionConversion());
            resolvers.add(MekanismItemToChemicalRecipeResolver.createPigmentExtracting());
            resolvers.add(MekanismMetallurgicInfusingRecipeResolver.createAudited());
            resolvers.add(MekanismGasToGasRecipeResolver.createAudited());
            resolvers.add(MekanismChemicalToItemRecipeResolver.createAudited());
            resolvers.add(MekanismChemicalInfusingRecipeResolver.createAudited());
            resolvers.add(MekanismPigmentMixingRecipeResolver.createAudited());
            resolvers.add(MekanismSeparatingRecipeResolver.createAudited());
            resolvers.add(MekanismWashingRecipeResolver.createAudited());
            resolvers.add(MekanismReactionRecipeResolver.createAudited());
            resolvers.add(MekanismRotaryRecipeResolver.createAudited());
        }
        return new MekanismRecipeAdapter(resolvers);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public List<RecipeType<?>> supportedRecipeTypes() {
        return types;
    }

    @Override
    public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
        if (recipe == null || context == null) {
            return Optional.empty();
        }
        MekanismRecipeFamilyResolver resolver = descriptors.get(recipe.getType());
        return resolver == null ? Optional.empty() : resolver.resolve(recipe, context);
    }
}
