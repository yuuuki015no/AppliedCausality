package dev.appliedcausality.internal.registry;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import dev.appliedcausality.internal.config.ConfiguredRuleSnapshot;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The sole internal resolver for author-declared standard static Item semantics. */
final class ConfiguredStandardStaticItemResolver {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            AppliedCausality.MOD_ID, "configured_standard_static_item");
    private static final ProcessProperties PROPERTIES = new ProcessProperties(true, true, true, true);

    private ConfiguredStandardStaticItemResolver() {
    }

    static Optional<ResolvedProcess> resolve(Recipe<?> recipe, RegistryAccess access,
            IPatternDetails pattern, ConfiguredRuleSnapshot.RecipeTypeProfile profile) {
        return inspect(recipe, access, pattern, profile).process();
    }

    /** Preserves a concrete refusal for author diagnostics without creating an executable candidate. */
    static Attempt inspect(Recipe<?> recipe, RegistryAccess access,
            IPatternDetails pattern, ConfiguredRuleSnapshot.RecipeTypeProfile profile) {
        if (recipe == null || access == null || pattern == null || profile == null) {
            return Attempt.rejected(ValidationDiagnostic.NO_RECIPE, "MISSING_RECIPE_CONTEXT");
        }
        if (!(pattern instanceof AEProcessingPattern)) {
            return Attempt.rejected(ValidationDiagnostic.UNSUPPORTED_PATTERN, "UNSUPPORTED_PATTERN");
        }
        if (profile.excludedRecipes().contains(recipe.getId())) {
            return Attempt.rejected(ValidationDiagnostic.NO_RECIPE, "EXCLUDED_RECIPE");
        }
        if (recipe.getType() != BuiltInRegistries.RECIPE_TYPE.get(profile.recipeType())
                || !profile.recipeType().equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))) {
            return Attempt.rejected(ValidationDiagnostic.RECIPE_TYPE_MISMATCH, "RECIPE_TYPE_MISMATCH");
        }
        if (recipe.getSerializer() == null
                || ForgeRegistries.RECIPE_SERIALIZERS.getKey(recipe.getSerializer()) == null) {
            return Attempt.rejected(ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED, "UNREGISTERED_SERIALIZER");
        }
        if (recipe.isIncomplete()) {
            return Attempt.rejected(ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED, "RECIPE_INCOMPLETE");
        }

        InputInspection patternInputs = concretePatternKeys(pattern);
        if (patternInputs.diagnostic() != ValidationDiagnostic.VALID) {
            return Attempt.rejected(patternInputs.diagnostic(), patternInputs.reason());
        }
        List<AEItemKey> patternKeys = patternInputs.keys();
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients == null || ingredients.isEmpty()) {
            return Attempt.rejected(ValidationDiagnostic.INPUT_MISMATCH, "EMPTY_INGREDIENTS");
        }

        List<AEItemKey> matchedKeys = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || !ingredient.isVanilla() || !supportedIngredient(ingredient.toJson())) {
                return Attempt.rejected(ValidationDiagnostic.SUBSTITUTION_UNSUPPORTED,
                        "UNSUPPORTED_INGREDIENT");
            }
            AEItemKey match = null;
            for (AEItemKey key : patternKeys) {
                ItemStack stack = key.getReadOnlyStack();
                if (ingredient.test(stack)) {
                    if (match != null && !match.equals(key)) {
                        return Attempt.rejected(ValidationDiagnostic.SUBSTITUTION_UNSUPPORTED,
                                "AMBIGUOUS_INGREDIENT");
                    }
                    match = key;
                }
            }
            if (match == null) {
                return Attempt.rejected(ValidationDiagnostic.INPUT_MISMATCH, "INGREDIENT_NOT_IN_PATTERN");
            }
            matchedKeys.add(match);
        }

        Map<appeng.api.stacks.AEKey, Long> inputs = new HashMap<>();
        for (AEItemKey match : matchedKeys) {
            ItemStack concrete = match.getReadOnlyStack();
            String inputState = unsupportedState(concrete, "PATTERN_INPUT");
            if (inputState != null) {
                return Attempt.rejectedMatched(ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED, inputState);
            }
            if (concrete.hasCraftingRemainingItem()) {
                return Attempt.rejectedMatched(ValidationDiagnostic.REMAINING_ITEMS_UNSUPPORTED,
                        "INPUT_HAS_CRAFTING_REMAINDER");
            }
            try {
                inputs.merge(match, 1L, Math::addExact);
            } catch (ArithmeticException exception) {
                return Attempt.rejectedMatched(ValidationDiagnostic.NUMERICALLY_UNSAFE, "INPUT_COUNT_OVERFLOW");
            }
        }

        ItemStack output = recipe.getResultItem(access);
        String outputState = unsupportedState(output, "RECIPE_OUTPUT");
        if (outputState != null) {
            return Attempt.rejectedForOutput(outputState.equals("RECIPE_OUTPUT_EMPTY")
                    ? ValidationDiagnostic.OUTPUT_MISMATCH : ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED,
                    outputState, pattern, output);
        }
        if (output.getCount() <= 0) {
            return Attempt.rejectedForOutput(ValidationDiagnostic.NUMERICALLY_UNSAFE,
                    "RECIPE_OUTPUT_COUNT_UNSAFE", pattern, output);
        }
        // Recipe<C> does not expose a type-safe way to construct C. Additional
        // machine/container semantics are part of the configured Type contract.
        AEItemKey outputKey = AEItemKey.of(output);
        if (outputKey == null) {
            return Attempt.rejectedForOutput(ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED,
                    "RECIPE_OUTPUT_KEY_UNSUPPORTED", pattern, output);
        }
        return Attempt.resolved(new ResolvedProcess(recipe.getId(), profile.recipeType(),
                new ProcessShapeId(profile.shape()), profile.family(), profile.machineProfile(),
                inputs, Map.of(outputKey, (long) output.getCount()), Map.of(), PROPERTIES));
    }

    private static InputInspection concretePatternKeys(IPatternDetails pattern) {
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        if (inputs == null || inputs.length == 0) {
            return InputInspection.rejected(ValidationDiagnostic.INPUT_MISMATCH, "PATTERN_INPUT_EMPTY");
        }
        List<AEItemKey> keys = new ArrayList<>(inputs.length);
        for (IPatternDetails.IInput input : inputs) {
            if (input == null || input.getMultiplier() <= 0) {
                return InputInspection.rejected(ValidationDiagnostic.NUMERICALLY_UNSAFE,
                        "PATTERN_INPUT_MULTIPLIER_UNSAFE");
            }
            GenericStack[] choices = input.getPossibleInputs();
            if (choices == null || choices.length != 1 || choices[0] == null
                    || choices[0].amount() <= 0
                    || !(choices[0].what() instanceof AEItemKey key)) {
                return InputInspection.rejected(ValidationDiagnostic.SUBSTITUTION_UNSUPPORTED,
                        "PATTERN_INPUT_NOT_ONE_ITEM");
            }
            String state = unsupportedState(key.getReadOnlyStack(), "PATTERN_INPUT");
            if (state != null) {
                return InputInspection.rejected(ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED, state);
            }
            if (input.getRemainingKey(key) != null) {
                return InputInspection.rejected(ValidationDiagnostic.REMAINING_ITEMS_UNSUPPORTED,
                        "PATTERN_INPUT_HAS_REMAINDER");
            }
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        return InputInspection.valid(keys);
    }

    private static boolean supportedIngredient(JsonElement json) {
        if (!(json instanceof JsonObject object) || object.size() != 1) {
            return false;
        }
        JsonElement id = object.has("item") ? object.get("item") : object.get("tag");
        return id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                && ResourceLocation.tryParse(id.getAsString()) != null;
    }

    private static String unsupportedState(ItemStack stack, String prefix) {
        if (stack == null || stack.isEmpty()) {
            return prefix + "_EMPTY";
        }
        if (stack.hasTag()) {
            return prefix + "_NBT";
        }
        if (stack.isDamageableItem()) {
            return prefix + "_DAMAGEABLE";
        }
        return stack.save(new CompoundTag()).contains("ForgeCaps") ? prefix + "_FORGE_CAPABILITY" : null;
    }

    private static boolean outputItemCouldMatch(IPatternDetails pattern, ItemStack output) {
        if (output == null || output.isEmpty()) {
            return true; // An empty result has no Item identity to compare.
        }
        GenericStack[] declared = pattern.getOutputs();
        if (declared == null) {
            return false;
        }
        for (GenericStack stack : declared) {
            if (stack != null && stack.what() instanceof AEItemKey key
                    && key.getReadOnlyStack().getItem() == output.getItem()) {
                return true;
            }
        }
        return false;
    }

    record Attempt(Optional<ResolvedProcess> process, ValidationDiagnostic diagnostic, String reason,
            boolean attributableToPattern) {
        static Attempt resolved(ResolvedProcess process) {
            return new Attempt(Optional.of(process), ValidationDiagnostic.VALID, "VALID", true);
        }

        static Attempt rejected(ValidationDiagnostic diagnostic, String reason) {
            return new Attempt(Optional.empty(), diagnostic, reason, false);
        }

        static Attempt rejectedMatched(ValidationDiagnostic diagnostic, String reason) {
            return new Attempt(Optional.empty(), diagnostic, reason, true);
        }

        static Attempt rejectedForOutput(ValidationDiagnostic diagnostic, String reason,
                IPatternDetails pattern, ItemStack output) {
            return new Attempt(Optional.empty(), diagnostic, reason,
                    outputItemCouldMatch(pattern, output));
        }
    }

    private record InputInspection(List<AEItemKey> keys, ValidationDiagnostic diagnostic, String reason) {
        static InputInspection valid(List<AEItemKey> keys) {
            return new InputInspection(List.copyOf(keys), ValidationDiagnostic.VALID, "VALID");
        }

        static InputInspection rejected(ValidationDiagnostic diagnostic, String reason) {
            return new InputInspection(List.of(), diagnostic, reason);
        }
    }
}
