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
import mekanism.api.recipes.ItemStackToInfuseTypeRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.InfusionConversionIRecipe;
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

/** Tests the exact resolver against active Mekanism recipes and mandatory Core validation. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalInfusionConversionResolverGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "infusion_conversion");
    private static final ResourceLocation PACK_ID = id("pack_test", "infusion_conversion");
    private static final ResourceLocation BUILTIN_ID = id("mekanism", "infusion_conversion/carbon/from_enriched");
    private static final ResourceLocation ARRAY_ID = id("mekanism", "infusion_conversion/carbon/from_coal");
    private static final ResourceLocation ADAPTER_ID = id(AppliedCausality.MOD_ID, "mekanism");
    private static final TagKey<Item> INPUT_TAG = TagKey.create(Registries.ITEM, id("pack_test", "fungi"));

    private ChemicalInfusionConversionResolverGameTests() {
    }

    @GameTest(template = "empty")
    public static void exactRecipesPassCoreAndUnsafeShapesReject(GameTestHelper helper) {
        var resolver = MekanismItemToChemicalRecipeResolver.createInfusionConversion();
        var manager = helper.getLevel().getRecipeManager();
        var pack = manager.byKey(PACK_ID).orElseThrow();
        require(pack.getClass() == InfusionConversionIRecipe.class
                        && pack.getType() == resolver.supportedRecipeType()
                        && manager.getAllRecipesFor(MekanismRecipeType.INFUSION_CONVERSION.get()).contains(pack)
                        && MekanismRecipeAdapter.createBundled().supportedRecipeTypes()
                                .contains(resolver.supportedRecipeType()),
                "infusion conversion must use its active registered Type");

        var valid = context(helper, Items.SPORE_BLOSSOM, 2L, pack, 17L);
        var candidate = resolver.resolve(pack, valid).orElseThrow();
        var outputKey = MekanismKey.of(((ItemStackToInfuseTypeRecipe) pack)
                .getOutput(new ItemStack(Items.SPORE_BLOSSOM)));
        require(candidate.recipeTypeId().equals(TYPE_ID)
                        && candidate.processFamily().equals(TYPE_ID)
                        && candidate.machineProfile().equals(id("mekanism", "metallurgic_infuser"))
                        && candidate.inputsPerOperation().equals(Map.of(AEItemKey.of(Items.SPORE_BLOSSOM), 2L))
                        && candidate.outputsPerOperation().equals(Map.of(outputKey, 17L)),
                "shared family changed the infusion conversion candidate contract");
        var verified = ProcessModelValidator.validate(new ProcessCandidate(ADAPTER_ID, 0, candidate), valid);
        require(verified.diagnostic() == ValidationDiagnostic.VALID
                        && verified.model().orElseThrow().recipeId().equals(PACK_ID)
                        && verified.model().orElseThrow().inputsPerOperation()
                                .get(AEItemKey.of(Items.SPORE_BLOSSOM)) == 2L
                        && verified.model().orElseThrow().outputsPerOperation()
                                .get(MekanismKey.of(((ItemStackToInfuseTypeRecipe) pack)
                                        .getOutput(new ItemStack(Items.SPORE_BLOSSOM)))) == 17L,
                "pack infusion conversion did not produce a Core-verified model");

        var wrongInput = context(helper, Items.GLOW_BERRIES, 2L, pack, 17L);
        var wrongAmount = context(helper, Items.SPORE_BLOSSOM, 3L, pack, 17L);
        var wrongOutput = context(helper, Items.SPORE_BLOSSOM, 2L, pack, 18L);
        require(resolver.resolve(pack, wrongInput).isEmpty()
                        && resolver.resolve(pack, wrongOutput).isEmpty()
                        && ProcessModelValidator.validate(new ProcessCandidate(ADAPTER_ID, 0, candidate), wrongAmount)
                                .diagnostic() == ValidationDiagnostic.INPUT_MISMATCH
                        && ProcessModelValidator.validate(new ProcessCandidate(ADAPTER_ID, 0, candidate), wrongOutput)
                                .diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH,
                "wrong infusion conversion Pattern passed resolver or Core");

        var builtin = manager.byKey(BUILTIN_ID).orElseThrow();
        ItemStack builtinInput = ((ItemStackToInfuseTypeRecipe) builtin).getInput()
                .getRepresentations().get(0);
        require(resolver.resolve(builtin, context(helper, builtinInput.getItem(), 1L, builtin, 80L)).isPresent(),
                "single-tag built-in infusion conversion was rejected");
        var array = manager.byKey(ARRAY_ID).orElseThrow();
        ItemStack arrayInput = ((ItemStackToInfuseTypeRecipe) array).getInput()
                .getRepresentations().get(0);
        require(resolver.resolve(array, context(helper, arrayInput.getItem(), 1L, array,
                ((ItemStackToInfuseTypeRecipe) array).getOutput(arrayInput).getAmount())).isEmpty(),
                "serialized array alternatives were accepted");
        var source = (InfusionConversionIRecipe) pack;
        Recipe<?> unknownSubclass = new InfusionConversionIRecipe(PACK_ID, source.getInput(),
                source.getOutput(new ItemStack(Items.SPORE_BLOSSOM))) { };
        require(resolver.resolve(unknownSubclass, valid).isEmpty(), "unknown recipe subclass was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_infusion_conversion_reload", timeoutTicks = 200)
    public static void replacementAndRemovalUseActiveRecipeManager(GameTestHelper helper) {
        var resolver = MekanismItemToChemicalRecipeResolver.createInfusionConversion();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var oldRecipe = manager.byKey(PACK_ID).orElseThrow();
        var oldPattern = context(helper, Items.SPORE_BLOSSOM, 2L, oldRecipe, 17L);
        var oldCandidate = resolver.resolve(oldRecipe, oldPattern).orElseThrow();
        try {
            Recipe<?> replacement = recipe(3, 19);
            replace(manager, original, replacement);
            var active = manager.byKey(PACK_ID).orElseThrow();
            require(active == replacement && resolver.resolve(active, oldPattern).isEmpty(),
                    "replacement kept the stale infusion conversion amounts");
            var updated = context(helper, Items.SPORE_BLOSSOM, 3L, active, 19L);
            var candidate = resolver.resolve(active, updated).orElseThrow();
            require(ProcessModelValidator.validate(new ProcessCandidate(ADAPTER_ID, 0, candidate), updated)
                            .diagnostic() == ValidationDiagnostic.VALID,
                    "replacement recipe was not Core-validated");
            replace(manager, original, null);
            require(manager.byKey(PACK_ID).isEmpty()
                            && ProcessModelValidator.validate(new ProcessCandidate(ADAPTER_ID, 0, oldCandidate),
                                    oldPattern).diagnostic() == ValidationDiagnostic.NO_RECIPE,
                    "removed recipe remained authoritative");
        } finally {
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_infusion_conversion_tag_reload", timeoutTicks = 200)
    public static void changedTagMembershipRejectsReparsedRecipe(GameTestHelper helper) {
        var resolver = MekanismItemToChemicalRecipeResolver.createInfusionConversion();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> originalRecipes = List.copyOf(manager.getRecipes());
        var loaded = manager.byKey(PACK_ID).orElseThrow();
        var pattern = context(helper, Items.SPORE_BLOSSOM, 2L, loaded, 17L);
        require(resolver.resolve(loaded, pattern).isPresent(), "initial tag recipe did not match");
        Map<TagKey<Item>, List<Holder<Item>>> originalTags = new IdentityHashMap<>();
        BuiltInRegistries.ITEM.getTags().forEach(pair ->
                originalTags.put(pair.getFirst(), pair.getSecond().stream().toList()));
        try {
            var changed = new IdentityHashMap<>(originalTags);
            changed.put(INPUT_TAG, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.DIRT)));
            BuiltInRegistries.ITEM.bindTags(changed);
            Ingredient.invalidateAll();
            replace(manager, originalRecipes, recipe(2, 17));
            require(resolver.resolve(manager.byKey(PACK_ID).orElseThrow(), pattern).isEmpty(),
                    "reparsed recipe retained stale tag membership");
        } finally {
            BuiltInRegistries.ITEM.bindTags(originalTags);
            Ingredient.invalidateAll();
            manager.replaceRecipes(originalRecipes);
        }
        require(resolver.resolve(manager.byKey(PACK_ID).orElseThrow(), pattern).isPresent(),
                "restored tag did not recover recipe");
        helper.succeed();
    }

    private static PatternContext context(GameTestHelper helper, Item input, long inputAmount,
            Recipe<?> recipe, long outputAmount) {
        var infusion = ((ItemStackToInfuseTypeRecipe) recipe).getOutput(new ItemStack(input));
        var key = MekanismKey.of(infusion);
        require(key != null && key.getForm() == MekanismKey.INFUSION,
                "Infusion key was unavailable");
        var definition = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(input), inputAmount)},
                new GenericStack[]{new GenericStack(key, outputAmount)});
        var pattern = PatternDetailsHelper.decodePattern(definition, helper.getLevel());
        require(pattern != null, "infusion conversion Pattern did not decode");
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static Recipe<?> recipe(int inputAmount, long outputAmount) {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "infusion conversion serializer is unavailable");
        String json = "{\"input\":{\"amount\":" + inputAmount
                + ",\"ingredient\":{\"tag\":\"pack_test:fungi\"}},\"output\":{\"amount\":"
                + outputAmount + ",\"infuse_type\":\"mekanism:carbon\"}}";
        return serializer.fromJson(PACK_ID, JsonParser.parseString(json).getAsJsonObject());
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original, Recipe<?> replacement) {
        List<Recipe<?>> next = new ArrayList<>(original);
        next.removeIf(recipe -> recipe.getId().equals(PACK_ID));
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
