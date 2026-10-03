package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.AEProcessingPattern;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.recipes.chemical.ItemStackToChemicalRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fixed Item-to-Chemical candidates for individually audited Mekanism Types. */
final class MekanismItemToChemicalRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ProcessShapeId SHAPE = ProcessShapeId.of(AppliedCausality.MOD_ID, "item_to_chemical");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final Descriptor descriptor;

    private MekanismItemToChemicalRecipeResolver(Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    static MekanismItemToChemicalRecipeResolver createOxidizing() {
        return createAudited("oxidizing", "mekanism.common.recipe.impl.ChemicalOxidizerIRecipe",
                "chemical_oxidizer", MekanismKey.GAS);
    }

    static MekanismItemToChemicalRecipeResolver createInfusionConversion() {
        return createAudited("infusion_conversion", "mekanism.common.recipe.impl.InfusionConversionIRecipe",
                "metallurgic_infuser", MekanismKey.INFUSION);
    }

    /** Exact gas conversion descriptor; shared tank conversion has no single machine profile. */
    static MekanismItemToChemicalRecipeResolver createGasConversion() {
        return createAudited("gas_conversion", "mekanism.common.recipe.impl.GasConversionIRecipe",
                null, MekanismKey.GAS);
    }

    /** Audited Item-to-Pigment descriptor, gated by Applied Mekanistics in the bundled adapter. */
    static MekanismItemToChemicalRecipeResolver createPigmentExtracting() {
        return createAudited("pigment_extracting", "mekanism.common.recipe.impl.PigmentExtractingIRecipe",
                "pigment_extractor", MekanismKey.PIGMENT);
    }

    private static MekanismItemToChemicalRecipeResolver createAudited(
            String path, String recipeClass, @Nullable String machinePath, int chemicalForm) {
        ResourceLocation typeId = ResourceLocation.fromNamespaceAndPath("mekanism", path);
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(typeId);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(typeId);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 recipe registry entry is unavailable: " + typeId);
        }
        return new MekanismItemToChemicalRecipeResolver(new Descriptor(typeId, type, serializer,
                recipeClass, machinePath == null ? null
                        : ResourceLocation.fromNamespaceAndPath("mekanism", machinePath), chemicalForm));
    }

    @Override
    public RecipeType<?> supportedRecipeType() {
        return descriptor.type();
    }

    @Override
    public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
        if (recipe == null || context == null || !(context.pattern() instanceof AEProcessingPattern)
                || !recipe.getClass().getName().equals(descriptor.recipeClass())
                || recipe.getType() != descriptor.type() || recipe.getSerializer() != descriptor.serializer()
                || !(recipe instanceof ItemStackToChemicalRecipe<?, ?> conversion) || conversion.isIncomplete()) {
            return Optional.empty();
        }

        IPatternDetails pattern = context.pattern();
        ItemStack input = MekanismItemToItemRecipeAdapter.concretePatternInput(pattern).orElse(null);
        if (input == null || MekanismItemToItemRecipeAdapter.hasUnsupportedState(input)) {
            return Optional.empty();
        }
        var amount = MekanismItemToItemRecipeAdapter.exactRequiredAmount(conversion.getInput(), input,
                MekanismItemToItemRecipeAdapter.SINGLE_INGREDIENT_CLASS);
        if (amount.isEmpty()) {
            return Optional.empty();
        }
        ItemStack recipeInput = input.copyWithCount(amount.orElseThrow());
        ItemStack matching = conversion.getInput().getMatchingInstance(recipeInput);
        if (!conversion.getInput().test(recipeInput) || matching.isEmpty()
                || matching.getCount() != amount.orElseThrow()
                || !ItemStack.isSameItemSameTags(recipeInput, matching)
                || MekanismItemToItemRecipeAdapter.hasUnsupportedState(matching)) {
            return Optional.empty();
        }

        ChemicalStack<?> output = conversion.getOutput(recipeInput);
        List<? extends ChemicalStack<?>> definition = conversion.getOutputDefinition();
        if (output == null || output.isEmpty() || output.getAmount() <= 0
                || definition == null || definition.size() != 1 || !output.equals(definition.get(0))) {
            return Optional.empty();
        }
        MekanismKey outputKey = MekanismKey.of(output);
        if (outputKey == null || outputKey.getForm() != descriptor.chemicalForm()
                || !matchesSinglePatternOutput(pattern, outputKey, output.getAmount())) {
            return Optional.empty();
        }
        AEItemKey inputKey = AEItemKey.of(input);
        if (inputKey == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), descriptor.typeId(), SHAPE,
                descriptor.typeId(), descriptor.machineId(),
                Map.of(inputKey, amount.orElseThrow().longValue()),
                Map.of(outputKey, output.getAmount()), Map.of(), PROPERTIES));
    }

    private static boolean matchesSinglePatternOutput(IPatternDetails pattern, MekanismKey key, long amount) {
        var outputs = pattern.getOutputs();
        return outputs != null && outputs.length == 1 && outputs[0] != null
                && outputs[0].amount() == amount && key.equals(outputs[0].what());
    }

    private record Descriptor(ResourceLocation typeId, RecipeType<?> type, RecipeSerializer<?> serializer,
                              String recipeClass, @Nullable ResourceLocation machineId, int chemicalForm) {
    }
}
