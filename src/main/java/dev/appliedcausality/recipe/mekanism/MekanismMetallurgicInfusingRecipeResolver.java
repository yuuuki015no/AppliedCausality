package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.AEProcessingPattern;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.recipes.MetallurgicInfuserRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Audited exact Item and Infusion candidate for mandatory Core validation. */
final class MekanismMetallurgicInfusingRecipeResolver implements MekanismRecipeFamilyResolver {
    private static final ResourceLocation TYPE_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "metallurgic_infusing");
    private static final ResourceLocation MACHINE_ID =
            ResourceLocation.fromNamespaceAndPath("mekanism", "metallurgic_infuser");
    private static final String RECIPE_CLASS = "mekanism.common.recipe.impl.MetallurgicInfuserIRecipe";
    private static final String SINGLE_INFUSION_CLASS =
            "mekanism.common.recipe.ingredient.creator.InfusionStackIngredientCreator$SingleInfusionStackIngredient";
    private static final String TAGGED_INFUSION_CLASS =
            "mekanism.common.recipe.ingredient.creator.InfusionStackIngredientCreator$TaggedInfusionStackIngredient";
    private static final ProcessShapeId SHAPE =
            ProcessShapeId.of(AppliedCausality.MOD_ID, "item_and_infusion_to_item");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private final RecipeType<?> type;
    private final RecipeSerializer<?> serializer;

    private MekanismMetallurgicInfusingRecipeResolver(RecipeType<?> type, RecipeSerializer<?> serializer) {
        this.type = type;
        this.serializer = serializer;
    }

    static MekanismMetallurgicInfusingRecipeResolver createAudited() {
        RecipeType<?> type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        if (type == null || serializer == null) {
            throw new IllegalStateException("Mekanism 10.4.16 metallurgic infusing registry entry is unavailable");
        }
        return new MekanismMetallurgicInfusingRecipeResolver(type, serializer);
    }

    @Override
    public RecipeType<?> supportedRecipeType() {
        return type;
    }

    @Override
    public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
        if (recipe == null || context == null || !(context.pattern() instanceof AEProcessingPattern)
                || !recipe.getClass().getName().equals(RECIPE_CLASS)
                || recipe.getType() != type || recipe.getSerializer() != serializer
                || !(recipe instanceof MetallurgicInfuserRecipe infusing) || infusing.isIncomplete()) {
            return Optional.empty();
        }

        Inputs inputs = concreteInputs(context.pattern()).orElse(null);
        if (inputs == null || MekanismItemToItemRecipeAdapter.hasUnsupportedState(inputs.item())) {
            return Optional.empty();
        }
        var itemAmount = MekanismItemToItemRecipeAdapter.exactRequiredAmount(
                infusing.getItemInput(), inputs.item(),
                MekanismItemToItemRecipeAdapter.SINGLE_INGREDIENT_CLASS);
        if (itemAmount.isEmpty()) {
            return Optional.empty();
        }
        ItemStack item = inputs.item().copyWithCount(itemAmount.orElseThrow());
        ItemStack matchedItem = infusing.getItemInput().getMatchingInstance(item);
        if (!infusing.getItemInput().test(item) || matchedItem.isEmpty()
                || matchedItem.getCount() != item.getCount()
                || !ItemStack.isSameItemSameTags(matchedItem, item)
                || MekanismItemToItemRecipeAdapter.hasUnsupportedState(matchedItem)) {
            return Optional.empty();
        }

        InfusionStack base = (InfusionStack) inputs.infusionKey().getStack();
        long chemicalAmount = exactChemicalAmount(infusing, base);
        if (chemicalAmount <= 0) {
            return Optional.empty();
        }
        InfusionStack infusion = new InfusionStack(base, chemicalAmount);
        InfusionStack matchedInfusion = infusing.getChemicalInput().getMatchingInstance(infusion);
        if (!infusing.getChemicalInput().test(infusion) || matchedInfusion.isEmpty()
                || matchedInfusion.getAmount() != chemicalAmount
                || !matchedInfusion.isTypeEqual(infusion)
                || !infusing.test(item, infusion)) {
            return Optional.empty();
        }

        ItemStack output = infusing.getOutput(item, infusion);
        List<ItemStack> definitions = infusing.getOutputDefinition();
        if (output == null || MekanismItemToItemRecipeAdapter.hasUnsupportedState(output)
                || definitions == null || definitions.size() != 1
                || !MekanismItemToItemRecipeAdapter.sameStackAndAmount(output, definitions.get(0))
                || !MekanismItemToItemRecipeAdapter.sameStackAndAmount(
                        output, infusing.getResultItem(context.registryAccess()))
                || !MekanismItemToItemRecipeAdapter.matchesSinglePatternOutput(context.pattern(), output)) {
            return Optional.empty();
        }
        AEItemKey itemKey = AEItemKey.of(item);
        AEItemKey outputKey = AEItemKey.of(output);
        if (itemKey == null || outputKey == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedProcess(recipe.getId(), TYPE_ID, SHAPE, TYPE_ID, MACHINE_ID,
                Map.of(itemKey, (long) itemAmount.orElseThrow(), inputs.infusionKey(), chemicalAmount),
                Map.of(outputKey, (long) output.getCount()), Map.of(), PROPERTIES));
    }

    private static Optional<Inputs> concreteInputs(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        if (inputs == null || inputs.length != 2) {
            return Optional.empty();
        }
        ItemStack item = null;
        MekanismKey infusion = null;
        for (var input : inputs) {
            if (input == null || input.getMultiplier() <= 0) {
                return Optional.empty();
            }
            var possible = input.getPossibleInputs();
            if (possible == null || possible.length != 1 || possible[0] == null
                    || possible[0].amount() <= 0) {
                return Optional.empty();
            }
            if (possible[0].what() instanceof AEItemKey itemKey && item == null) {
                item = itemKey.getReadOnlyStack();
            } else if (possible[0].what() instanceof MekanismKey chemicalKey && infusion == null
                    && chemicalKey.getForm() == MekanismKey.INFUSION
                    && chemicalKey.getStack() instanceof InfusionStack) {
                infusion = chemicalKey;
            } else {
                return Optional.empty();
            }
        }
        return item == null || infusion == null ? Optional.empty() : Optional.of(new Inputs(item, infusion));
    }

    private static long exactChemicalAmount(MetallurgicInfuserRecipe recipe, InfusionStack input) {
        var ingredient = recipe.getChemicalInput();
        String className = ingredient.getClass().getName();
        JsonElement serialized = ingredient.serialize();
        if (serialized == null || !serialized.isJsonObject()) {
            return 0;
        }
        JsonObject object = serialized.getAsJsonObject();
        String key = className.equals(SINGLE_INFUSION_CLASS) ? "infuse_type"
                : className.equals(TAGGED_INFUSION_CLASS) ? "tag" : null;
        if (key == null || !object.keySet().equals(Set.of("amount", key))) {
            return 0;
        }
        JsonElement id = object.get(key);
        JsonElement amountElement = object.get("amount");
        if (!(id instanceof JsonPrimitive name) || !name.isString()
                || ResourceLocation.tryParse(name.getAsString()) == null
                || !(amountElement instanceof JsonPrimitive number) || !number.isNumber()) {
            return 0;
        }
        try {
            long declared = number.getAsBigDecimal().longValueExact();
            return declared > 0 && ingredient.testType(input)
                    && ingredient.getNeededAmount(input) == declared ? declared : 0;
        } catch (ArithmeticException | NumberFormatException exception) {
            return 0;
        }
    }

    private record Inputs(ItemStack item, MekanismKey infusionKey) {
    }
}
