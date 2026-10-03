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
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import mekanism.common.recipe.impl.ChemicalCrystallizerIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks exact four-form crystallizing candidates against active Core recipe authority. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalCrystallizingResolverGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "crystallizing");
    private static final ResourceLocation GAS_ID = id("pack_test", "crystallizing_gas");

    private ChemicalCrystallizingResolverGameTests() {
    }

    @GameTest(template = "empty")
    public static void allFourFormsPassExactCoreGate(GameTestHelper helper) {
        verify(helper, "crystallizing_gas", gas("hydrogen", 2), 2, new ItemStack(Items.DIAMOND, 3));
        verify(helper, "crystallizing_infusion",
                MekanismAPI.infuseTypeRegistry().getValue(id("mekanism", "carbon")).getStack(11),
                11, new ItemStack(Items.EMERALD, 4));
        verify(helper, "crystallizing_pigment",
                MekanismAPI.pigmentRegistry().getValue(id("mekanism", "red")).getStack(13),
                13, new ItemStack(Items.LAPIS_LAZULI, 5));
        verify(helper, "crystallizing_slurry",
                MekanismAPI.slurryRegistry().getValue(id("mekanism", "clean_iron")).getStack(17),
                17, new ItemStack(Items.QUARTZ, 6));
        var builtIn = helper.getLevel().getRecipeManager()
                .byKey(id("mekanism", "crystallizing/salt")).orElseThrow();
        var builtInPattern = context(helper, gas("brine", 15), 15,
                new ItemStack(ForgeRegistries.ITEMS.getValue(id("mekanism", "salt"))));
        var builtInCandidate = MekanismChemicalToItemRecipeResolver.createAudited()
                .resolve(builtIn, builtInPattern).orElseThrow();
        require(ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                        builtInCandidate), builtInPattern).diagnostic() == ValidationDiagnostic.VALID,
                "built-in Gas crystallizing recipe did not pass Core");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wrongPatternsAndUnknownIngredientsFailClosed(GameTestHelper helper) {
        var recipe = helper.getLevel().getRecipeManager().byKey(GAS_ID).orElseThrow();
        var resolver = MekanismChemicalToItemRecipeResolver.createAudited();
        var valid = context(helper, gas("hydrogen", 2), 2, new ItemStack(Items.DIAMOND, 3));
        var candidate = resolver.resolve(recipe, valid).orElseThrow();
        var wrongAmount = context(helper, gas("hydrogen", 3), 3, new ItemStack(Items.DIAMOND, 3));
        var wrongGas = context(helper, gas("oxygen", 2), 2, new ItemStack(Items.DIAMOND, 3));
        var wrongForm = context(helper,
                MekanismAPI.infuseTypeRegistry().getValue(id("mekanism", "carbon")).getStack(2),
                2, new ItemStack(Items.DIAMOND, 3));
        var wrongOutput = context(helper, gas("hydrogen", 2), 2, new ItemStack(Items.DIAMOND, 4));
        require(resolver.resolve(recipe, wrongAmount).isEmpty()
                        && resolver.resolve(recipe, wrongGas).isEmpty()
                        && resolver.resolve(recipe, wrongForm).isEmpty()
                        && resolver.resolve(recipe, wrongOutput).isEmpty()
                        && ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                                candidate), wrongOutput).diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH,
                "crystallizing accepted wrong input form, amount, type, or Item output");

        var original = (ChemicalCrystallizerIRecipe) recipe;
        Recipe<?> subclass = new ChemicalCrystallizerIRecipe(GAS_ID, original.getInput(),
                original.getOutput(mekanism.api.chemical.merged.BoxedChemicalStack.box(gas("hydrogen", 2)))) { };
        require(resolver.resolve(subclass, valid).isEmpty(), "unknown crystallizing subclass was accepted");
        Recipe<?> multi = recipe(id("pack_test", "crystallizing_multi"),
                "[{\"amount\":2,\"gas\":\"mekanism:hydrogen\"},"
                        + "{\"amount\":2,\"gas\":\"mekanism:oxygen\"}]", 3);
        require(resolver.resolve(multi, valid).isEmpty(), "multi-Gas ingredient was accepted");
        Recipe<?> tag = recipe(id("pack_test", "crystallizing_tag"),
                "{\"amount\":2,\"tag\":\"pack_test:missing_gas_tag\"}", 3);
        require(resolver.resolve(tag, valid).isEmpty(), "unverified Gas tag was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_crystallizing_recipe_reload", timeoutTicks = 200)
    public static void replacementAndRemovalUseActiveRecipeManager(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var resolver = MekanismChemicalToItemRecipeResolver.createAudited();
        var oldPattern = context(helper, gas("hydrogen", 2), 2, new ItemStack(Items.DIAMOND, 3));
        var oldRecipe = manager.byKey(GAS_ID).orElseThrow();
        require(resolver.resolve(oldRecipe, oldPattern).isPresent(),
                "pack crystallizing recipe was not initially resolvable");
        try {
            Recipe<?> normalizedSingleton = recipe(GAS_ID,
                    "[{\"amount\":2,\"gas\":\"mekanism:hydrogen\"}]", 3);
            replace(manager, original, normalizedSingleton);
            var singleton = (ChemicalCrystallizerIRecipe) normalizedSingleton;
            var direct = (ChemicalCrystallizerIRecipe) oldRecipe;
            require(singleton.getInput().getClass() == direct.getInput().getClass()
                            && singleton.getInput().serialize().equals(direct.getInput().serialize()),
                    "Mekanism no longer normalizes singleton Chemical arrays to direct semantics");
            var singletonCandidate = resolver.resolve(normalizedSingleton, oldPattern).orElseThrow();
            require(ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                            singletonCandidate), oldPattern).diagnostic() == ValidationDiagnostic.VALID,
                    "normalized singleton Chemical array did not pass active Core validation");

            Recipe<?> replacement = recipe(GAS_ID,
                    "{\"amount\":3,\"gas\":\"mekanism:hydrogen\"}", 4);
            replace(manager, original, replacement);
            require(resolver.resolve(manager.byKey(GAS_ID).orElseThrow(), oldPattern).isEmpty(),
                    "changed crystallizing recipe accepted stale Pattern quantities");
            var updated = context(helper, gas("hydrogen", 3), 3, new ItemStack(Items.DIAMOND, 4));
            var candidate = resolver.resolve(replacement, updated).orElseThrow();
            require(ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                            candidate), updated).diagnostic() == ValidationDiagnostic.VALID,
                    "replacement crystallizing recipe did not pass active Core validation");
            replace(manager, original, null);
            require(manager.byKey(GAS_ID).isEmpty()
                            && ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                                    candidate), updated).diagnostic() == ValidationDiagnostic.NO_RECIPE,
                    "removed crystallizing recipe retained Core authority");
        } finally {
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    private static void verify(GameTestHelper helper, String path, ChemicalStack<?> input,
            long inputAmount, ItemStack output) {
        var recipe = helper.getLevel().getRecipeManager().byKey(id("pack_test", path)).orElseThrow();
        var pattern = context(helper, input, inputAmount, output);
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, pattern).orElseThrow();
        var inputKey = MekanismKey.of(input);
        var outputKey = AEItemKey.of(output);
        require(candidate.recipeTypeId().equals(TYPE_ID)
                        && candidate.processFamily().equals(TYPE_ID)
                        && candidate.machineProfile().equals(id("mekanism", "chemical_crystallizer"))
                        && candidate.inputsPerOperation().equals(Map.of(inputKey, inputAmount))
                        && candidate.outputsPerOperation().equals(Map.of(outputKey, (long) output.getCount())),
                "crystallizing candidate changed audited form, quantity, or metadata: " + path);
        var result = ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                candidate), pattern);
        require(result.diagnostic() == ValidationDiagnostic.VALID
                        && result.model().orElseThrow().recipeId().equals(recipe.getId()),
                "active crystallizing recipe did not pass Core: " + path);
    }

    private static PatternContext context(GameTestHelper helper, ChemicalStack<?> input,
            long amount, ItemStack output) {
        var inputKey = MekanismKey.of(input);
        var outputKey = AEItemKey.of(output);
        require(inputKey != null && outputKey != null, "crystallizing Pattern key was unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(inputKey, amount)},
                new GenericStack[]{new GenericStack(outputKey, output.getCount())}), helper.getLevel());
        require(pattern != null, "crystallizing Processing Pattern did not decode");
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static mekanism.api.chemical.gas.GasStack gas(String name, long amount) {
        return MekanismAPI.gasRegistry().getValue(id("mekanism", name)).getStack(amount);
    }

    private static Recipe<?> recipe(ResourceLocation id, String inputJson, int outputCount) {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "crystallizing serializer was unavailable");
        String json = "{\"chemicalType\":\"gas\",\"input\":" + inputJson
                + ",\"output\":{\"item\":\"minecraft:diamond\",\"count\":"
                + outputCount + "}}";
        return serializer.fromJson(id, JsonParser.parseString(json).getAsJsonObject());
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original, Recipe<?> replacement) {
        List<Recipe<?>> next = new ArrayList<>(original);
        next.removeIf(recipe -> recipe.getId().equals(GAS_ID));
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
