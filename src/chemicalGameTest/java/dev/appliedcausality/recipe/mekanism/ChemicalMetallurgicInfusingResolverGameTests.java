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
import mekanism.api.recipes.MetallurgicInfuserRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.MetallurgicInfuserIRecipe;
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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Exact recipe and Core gates for the bundled two-input Metallurgic Infusing Type. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalMetallurgicInfusingResolverGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "metallurgic_infusing");
    private static final ResourceLocation BUILTIN_ID = id("mekanism", "metallurgic_infusing/mossy/cobblestone");
    private static final ResourceLocation PACK_ID = id("pack_test", "metallurgic_infusing");
    private static final TagKey<Item> INPUT_TAG = TagKey.create(Registries.ITEM, id("pack_test", "fungi"));

    private ChemicalMetallurgicInfusingResolverGameTests() {
    }

    @GameTest(template = "empty")
    public static void screenshotRecipeAndPackRecipePassCore(GameTestHelper helper) {
        var resolver = MekanismMetallurgicInfusingRecipeResolver.createAudited();
        var manager = helper.getLevel().getRecipeManager();
        var builtin = (MetallurgicInfuserRecipe) manager.byKey(BUILTIN_ID).orElseThrow();
        var pack = (MetallurgicInfuserRecipe) manager.byKey(PACK_ID).orElseThrow();
        require(builtin.getClass() == MetallurgicInfuserIRecipe.class
                        && pack.getClass() == MetallurgicInfuserIRecipe.class
                        && builtin.getType() == MekanismRecipeType.METALLURGIC_INFUSING.get()
                        && pack.getType() == builtin.getType()
                        && pack.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && manager.getAllRecipesFor(MekanismRecipeType.METALLURGIC_INFUSING.get()).contains(pack)
                        && MekanismRecipeAdapter.createBundled().supportedRecipeTypes().contains(pack.getType()),
                "Metallurgic Infusing must use its active bundled Type");

        var builtinChemical = MekanismKey.of(builtin.getChemicalInput().getRepresentations().get(0));
        var builtinContext = context(helper, Items.COBBLESTONE, 1, builtinChemical, 10,
                Items.MOSSY_COBBLESTONE, 1);
        var builtinModel = verified(resolver, builtin, builtinContext);
        require(builtinModel.inputsPerOperation().get(AEItemKey.of(Items.COBBLESTONE)) == 1L
                        && builtinModel.inputsPerOperation().get(builtinChemical) == 10L
                        && builtinModel.outputsPerOperation().get(AEItemKey.of(Items.MOSSY_COBBLESTONE)) == 1L,
                "screenshot recipe changed exact Item/Infusion quantities");

        var packChemical = MekanismKey.of(pack.getChemicalInput().getRepresentations().get(0));
        var packContext = context(helper, Items.SPORE_BLOSSOM, 2, packChemical, 17,
                Items.ECHO_SHARD, 1);
        var packModel = verified(resolver, pack, packContext);
        require(packModel.inputsPerOperation().get(AEItemKey.of(Items.SPORE_BLOSSOM)) == 2L
                        && packModel.inputsPerOperation().get(packChemical) == 17L,
                "pack recipe did not preserve two-input quantities");

        var wrongChemicalAmount = context(helper, Items.COBBLESTONE, 1, builtinChemical, 11,
                Items.MOSSY_COBBLESTONE, 1);
        var wrongOutput = context(helper, Items.COBBLESTONE, 1, builtinChemical, 10,
                Items.MOSSY_COBBLESTONE, 2);
        var candidate = resolver.resolve(builtin, builtinContext).orElseThrow();
        require(ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                                candidate), wrongChemicalAmount).diagnostic() == ValidationDiagnostic.INPUT_MISMATCH
                        && resolver.resolve(builtin, wrongOutput).isEmpty()
                        && ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID,
                                0, candidate), wrongOutput).diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH,
                "wrong two-input Pattern passed resolver or Core");

        Recipe<?> unknown = new MetallurgicInfuserIRecipe(BUILTIN_ID, builtin.getItemInput(),
                builtin.getChemicalInput(), builtin.getOutput(new ItemStack(Items.COBBLESTONE),
                builtin.getChemicalInput().getRepresentations().get(0))) { };
        require(resolver.resolve(unknown, builtinContext).isEmpty(), "unknown recipe subclass was accepted");
        require(resolver.resolve(itemArrayRecipe(), packContext).isEmpty(),
                "serialized Item alternatives were accepted");
        require(resolver.resolve(chemicalArrayRecipe(), packContext).isEmpty(),
                "serialized Infusion alternatives were accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_metallurgic_reload", timeoutTicks = 200)
    public static void replacementAndRemovalUseActiveRecipeManager(GameTestHelper helper) {
        var resolver = MekanismMetallurgicInfusingRecipeResolver.createAudited();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var loaded = (MetallurgicInfuserRecipe) manager.byKey(PACK_ID).orElseThrow();
        var chemical = MekanismKey.of(loaded.getChemicalInput().getRepresentations().get(0));
        var oldContext = context(helper, Items.SPORE_BLOSSOM, 2, chemical, 17, Items.ECHO_SHARD, 1);
        var oldCandidate = resolver.resolve(loaded, oldContext).orElseThrow();
        try {
            Recipe<?> replacement = recipe(3, 19);
            replace(manager, original, replacement);
            var changedCandidate = resolver.resolve(replacement, oldContext).orElseThrow();
            require(ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                            changedCandidate), oldContext).diagnostic() == ValidationDiagnostic.INPUT_MISMATCH,
                    "same-ID replacement kept stale quantities at Core");
            verified(resolver, replacement,
                    context(helper, Items.SPORE_BLOSSOM, 3, chemical, 19, Items.ECHO_SHARD, 1));
            replace(manager, original, null);
            require(ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                            oldCandidate), oldContext).diagnostic() == ValidationDiagnostic.NO_RECIPE,
                    "removed Metallurgic Infusing recipe remained authoritative");
        } finally {
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_metallurgic_tag_reload", timeoutTicks = 200)
    public static void changedItemTagRejectsReparsedTwoInputRecipe(GameTestHelper helper) {
        var resolver = MekanismMetallurgicInfusingRecipeResolver.createAudited();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> originalRecipes = List.copyOf(manager.getRecipes());
        var loaded = (MetallurgicInfuserRecipe) manager.byKey(PACK_ID).orElseThrow();
        var chemical = MekanismKey.of(loaded.getChemicalInput().getRepresentations().get(0));
        var pattern = context(helper, Items.SPORE_BLOSSOM, 2, chemical, 17, Items.ECHO_SHARD, 1);
        require(resolver.resolve(loaded, pattern).isPresent(), "initial two-input tag recipe did not match");
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
                    "reparsed two-input recipe retained stale Item tag membership");
        } finally {
            BuiltInRegistries.ITEM.bindTags(originalTags);
            Ingredient.invalidateAll();
            manager.replaceRecipes(originalRecipes);
        }
        require(resolver.resolve(manager.byKey(PACK_ID).orElseThrow(), pattern).isPresent(),
                "restored Item tag did not recover two-input recipe");
        helper.succeed();
    }

    private static dev.appliedcausality.internal.validation.VerifiedProcessModel verified(
            MekanismMetallurgicInfusingRecipeResolver resolver, Recipe<?> recipe, PatternContext context) {
        var candidate = resolver.resolve(recipe, context).orElseThrow();
        var result = ProcessModelValidator.validate(new ProcessCandidate(
                MekanismRecipeAdapter.ID, 0, candidate), context);
        require(result.diagnostic() == ValidationDiagnostic.VALID, "Core rejected exact Metallurgic Infusing");
        return result.model().orElseThrow();
    }

    private static PatternContext context(GameTestHelper helper, Item input, long inputAmount,
            MekanismKey chemical, long chemicalAmount, Item output, long outputAmount) {
        require(chemical != null && chemical.getForm() == MekanismKey.INFUSION,
                "Infusion Pattern key was unavailable");
        var definition = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(input), inputAmount),
                        new GenericStack(chemical, chemicalAmount)},
                new GenericStack[]{new GenericStack(AEItemKey.of(output), outputAmount)});
        var pattern = PatternDetailsHelper.decodePattern(definition, helper.getLevel());
        require(pattern != null, "Metallurgic Infusing Pattern did not decode");
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static Recipe<?> itemArrayRecipe() {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        String json = "{\"itemInput\":{\"amount\":2,\"ingredient\":[{\"item\":\"minecraft:spore_blossom\"},"
                + "{\"item\":\"minecraft:dirt\"}]},\"chemicalInput\":{\"amount\":17,"
                + "\"infuse_type\":\"mekanism:carbon\"},\"output\":{\"item\":\"minecraft:echo_shard\"}}";
        return serializer.fromJson(PACK_ID, JsonParser.parseString(json).getAsJsonObject());
    }

    private static Recipe<?> chemicalArrayRecipe() {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        String json = "{\"itemInput\":{\"amount\":2,\"ingredient\":{\"tag\":\"pack_test:fungi\"}},"
                + "\"chemicalInput\":[{\"amount\":17,\"infuse_type\":\"mekanism:carbon\"},"
                + "{\"amount\":17,\"infuse_type\":\"mekanism:bio\"}],"
                + "\"output\":{\"item\":\"minecraft:echo_shard\"}}";
        return serializer.fromJson(PACK_ID, JsonParser.parseString(json).getAsJsonObject());
    }

    private static Recipe<?> recipe(int itemAmount, long chemicalAmount) {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        String json = "{\"itemInput\":{\"amount\":" + itemAmount
                + ",\"ingredient\":{\"tag\":\"pack_test:fungi\"}},\"chemicalInput\":{\"amount\":"
                + chemicalAmount + ",\"infuse_type\":\"mekanism:carbon\"},"
                + "\"output\":{\"item\":\"minecraft:echo_shard\"}}";
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
