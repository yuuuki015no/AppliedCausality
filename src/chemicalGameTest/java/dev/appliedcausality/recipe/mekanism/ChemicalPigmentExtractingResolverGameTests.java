package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonParser;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ProcessModelValidator;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.recipes.ItemStackToPigmentRecipe;
import mekanism.api.recipes.ItemStackToInfuseTypeRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.PigmentExtractingIRecipe;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Exercises bundled pigment extraction against active server recipe authority. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalPigmentExtractingResolverGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "pigment_extracting");
    private static final ResourceLocation RECIPE_ID = id("pack_test", "pigment_extracting");
    private static final ResourceLocation ADAPTER_ID = MekanismRecipeAdapter.ID;
    private static final TagKey<Item> INPUT_TAG = TagKey.create(Registries.ITEM, id("pack_test", "fungi"));

    private ChemicalPigmentExtractingResolverGameTests() {
    }

    @GameTest(template = "empty")
    public static void packRecipeUsesActiveManagerAndCoreValidation(GameTestHelper helper) {
        var resolver = MekanismItemToChemicalRecipeResolver.createPigmentExtracting();
        var manager = helper.getLevel().getRecipeManager();
        var loaded = manager.byKey(RECIPE_ID).orElseThrow();
        require(loaded.getClass() == PigmentExtractingIRecipe.class
                        && loaded.getType() == resolver.supportedRecipeType()
                        && manager.getAllRecipesFor(MekanismRecipeType.PIGMENT_EXTRACTING.get()).contains(loaded)
                        && MekanismRecipeAdapter.createBundled().supportedRecipeTypes()
                                .contains(resolver.supportedRecipeType()),
                "pack pigment extraction recipe was not loaded under the Mekanism Type");
        var valid = context(helper, Items.SPORE_BLOSSOM, 2L, loaded, 17L);
        var candidate = resolver.resolve(loaded, valid).orElseThrow();
        var outputKey = MekanismKey.of(((ItemStackToPigmentRecipe) loaded)
                .getOutput(new ItemStack(Items.SPORE_BLOSSOM)));
        require(candidate.recipeTypeId().equals(TYPE_ID)
                        && candidate.processFamily().equals(TYPE_ID)
                        && candidate.machineProfile().equals(id("mekanism", "pigment_extractor"))
                        && candidate.inputsPerOperation().equals(Map.of(AEItemKey.of(Items.SPORE_BLOSSOM), 2L))
                        && candidate.outputsPerOperation().equals(Map.of(outputKey, 17L)),
                "shared family changed the pigment extraction candidate contract");
        var result = ProcessModelValidator.validate(new ProcessCandidate(ADAPTER_ID, 0, candidate), valid);
        require(result.diagnostic() == ValidationDiagnostic.VALID
                        && result.model().orElseThrow().recipeId().equals(RECIPE_ID)
                        && result.model().orElseThrow().machineProfile()
                                .equals(id("mekanism", "pigment_extractor"))
                        && result.model().orElseThrow().inputsPerOperation().get(AEItemKey.of(Items.SPORE_BLOSSOM)) == 2L
                        && result.model().orElseThrow().outputsPerOperation().values().iterator().next() == 17L,
                "pack pigment extraction candidate did not pass exact Core validation");

        var wrongInput = context(helper, Items.GLOW_BERRIES, 2L, loaded, 17L);
        var wrongAmount = context(helper, Items.SPORE_BLOSSOM, 3L, loaded, 17L);
        var wrongOutput = context(helper, Items.SPORE_BLOSSOM, 2L, loaded, 18L);
        var blue = manager.byKey(id("mekanism", "pigment_extracting/wool/blue")).orElseThrow();
        var wrongColor = context(helper, Items.SPORE_BLOSSOM, 2L, blue, 17L);
        require(resolver.resolve(loaded, wrongInput).isEmpty()
                        && resolver.resolve(loaded, wrongOutput).isEmpty()
                        && resolver.resolve(loaded, wrongColor).isEmpty()
                        && ProcessModelValidator.validate(new ProcessCandidate(ADAPTER_ID, 0, candidate), wrongAmount)
                                .diagnostic() == ValidationDiagnostic.INPUT_MISMATCH
                        && ProcessModelValidator.validate(new ProcessCandidate(ADAPTER_ID, 0, candidate), wrongOutput)
                                .diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH,
                "wrong pigment extraction Pattern was accepted");
        var source = (PigmentExtractingIRecipe) loaded;
        Recipe<?> unknownSubclass = new PigmentExtractingIRecipe(RECIPE_ID, source.getInput(),
                source.getOutput(new ItemStack(Items.SPORE_BLOSSOM))) { };
        require(resolver.resolve(unknownSubclass, valid).isEmpty(), "unknown recipe subclass was accepted");
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "pigment extraction serializer is unavailable");
        var arrayRecipe = serializer.fromJson(id("pack_test", "pigment_extracting_array"),
                JsonParser.parseString("{\"input\":{\"amount\":2,\"ingredient\":[{\"item\":\"minecraft:spore_blossom\"},"
                        + "{\"item\":\"minecraft:dirt\"}]},\"output\":{\"amount\":17,"
                        + "\"pigment\":\"mekanism:red\"}}").getAsJsonObject());
        require(resolver.resolve(arrayRecipe, valid).isEmpty(),
                "serialized Item array was accepted");
        var builtIn = manager.byKey(id("mekanism", "pigment_extracting/wool/red")).orElseThrow();
        require(resolver.resolve(builtIn, context(helper, Items.RED_WOOL, 1L, builtIn, 192L)).isPresent(),
                "built-in pigment extraction recipe was rejected");
        var infusion = manager.byKey(id("pack_test", "infusion_conversion")).orElseThrow();
        var infusionKey = MekanismKey.of(((ItemStackToInfuseTypeRecipe) infusion)
                .getOutput(new ItemStack(Items.SPORE_BLOSSOM)));
        var infusionPattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.SPORE_BLOSSOM), 2)},
                new GenericStack[]{new GenericStack(infusionKey, 17)}), helper.getLevel());
        require(infusionPattern != null && resolver.resolve(infusion,
                        new PatternContext(helper.getLevel(), infusionPattern)).isEmpty()
                        && resolver.resolve(loaded,
                                new PatternContext(helper.getLevel(), infusionPattern)).isEmpty()
                        && MekanismItemToChemicalRecipeResolver.createInfusionConversion()
                                .resolve(loaded, valid).isEmpty(),
                "one Type's descriptor accepted the other Type's recipe");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_pigment_extracting_recipe_reload", timeoutTicks = 200)
    public static void replacementAndRemovalUseCurrentRecipeManager(GameTestHelper helper) {
        var resolver = MekanismItemToChemicalRecipeResolver.createPigmentExtracting();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var oldRecipe = manager.byKey(RECIPE_ID).orElseThrow();
        var oldPattern = context(helper, Items.SPORE_BLOSSOM, 2L, oldRecipe, 17L);
        try {
            Recipe<?> replacement = recipe(RECIPE_ID, 3, 19);
            replace(manager, original, replacement);
            var active = manager.byKey(RECIPE_ID).orElseThrow();
            require(active == replacement && resolver.resolve(active, oldPattern).isEmpty(),
                    "replaced pigment extraction recipe retained stale quantities");
            var updated = context(helper, Items.SPORE_BLOSSOM, 3L, active, 19L);
            var candidate = resolver.resolve(active, updated).orElseThrow();
            require(ProcessModelValidator.validate(new ProcessCandidate(ADAPTER_ID, 0, candidate), updated)
                            .diagnostic() == ValidationDiagnostic.VALID,
                    "replacement recipe was not revalidated");
            replace(manager, original, null);
            require(manager.byKey(RECIPE_ID).isEmpty()
                            && ProcessModelValidator.validate(new ProcessCandidate(ADAPTER_ID, 0, candidate),
                                    updated).diagnostic() == ValidationDiagnostic.NO_RECIPE,
                    "removed pigment extraction recipe remained authoritative");
        } finally {
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_pigment_extracting_tag_reload", timeoutTicks = 200)
    public static void changedTagMembershipRejectsCandidate(GameTestHelper helper) {
        var resolver = MekanismItemToChemicalRecipeResolver.createPigmentExtracting();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> originalRecipes = List.copyOf(manager.getRecipes());
        var loaded = manager.byKey(RECIPE_ID).orElseThrow();
        var pattern = context(helper, Items.SPORE_BLOSSOM, 2L, loaded, 17L);
        require(resolver.resolve(loaded, pattern).isPresent(), "initial tag-backed recipe did not match");
        Map<TagKey<Item>, List<Holder<Item>>> original = new IdentityHashMap<>();
        BuiltInRegistries.ITEM.getTags().forEach(pair ->
                original.put(pair.getFirst(), pair.getSecond().stream().toList()));
        try {
            var changed = new IdentityHashMap<>(original);
            changed.put(INPUT_TAG, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.DIRT)));
            BuiltInRegistries.ITEM.bindTags(changed);
            Ingredient.invalidateAll();
            replace(manager, originalRecipes, recipe(RECIPE_ID, 2, 17));
            var reloaded = manager.byKey(RECIPE_ID).orElseThrow();
            require(resolver.resolve(reloaded, pattern).isEmpty(),
                    "reloaded recipe retained stale tag membership");
        } finally {
            BuiltInRegistries.ITEM.bindTags(original);
            Ingredient.invalidateAll();
            manager.replaceRecipes(originalRecipes);
        }
        require(resolver.resolve(manager.byKey(RECIPE_ID).orElseThrow(), pattern).isPresent(),
                "restored tag did not recover recipe");
        helper.succeed();
    }

    private static PatternContext context(GameTestHelper helper, Item input, long inputAmount,
            Recipe<?> recipe, long outputAmount) {
        ItemStack recipeInput = new ItemStack(input);
        var pigment = ((ItemStackToPigmentRecipe) recipe).getOutput(recipeInput);
        var key = MekanismKey.of(pigment);
        require(key != null && key.getForm() == MekanismKey.PIGMENT,
                "pack pigment extraction Pigment key is unavailable");
        var definition = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(input), inputAmount)},
                new GenericStack[]{new GenericStack(key, outputAmount)});
        var pattern = PatternDetailsHelper.decodePattern(definition, helper.getLevel());
        require(pattern != null, "pack pigment extraction Pattern did not decode");
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static Recipe<?> recipe(ResourceLocation id, int inputAmount, long outputAmount) {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "pigment extraction serializer is unavailable");
        String json = "{\"input\":{\"amount\":" + inputAmount
                + ",\"ingredient\":{\"tag\":\"pack_test:fungi\"}},\"output\":{\"amount\":"
                + outputAmount + ",\"pigment\":\"mekanism:red\"}}";
        return serializer.fromJson(id, JsonParser.parseString(json).getAsJsonObject());
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original, Recipe<?> replacement) {
        List<Recipe<?>> next = new ArrayList<>(original);
        next.removeIf(recipe -> recipe.getId().equals(RECIPE_ID));
        if (replacement != null) {
            next.add(replacement);
        }
        manager.replaceRecipes(next);
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
