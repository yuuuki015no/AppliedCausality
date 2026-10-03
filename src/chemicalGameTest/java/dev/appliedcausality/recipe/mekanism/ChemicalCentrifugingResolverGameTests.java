package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonParser;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ProcessModelValidator;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.CentrifugingIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks the canonical centrifuging candidate against live recipe authority. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalCentrifugingResolverGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "centrifuging");
    private static final ResourceLocation RECIPE_ID = id("pack_test", "centrifuging");

    private ChemicalCentrifugingResolverGameTests() {
    }

    @GameTest(template = "empty")
    public static void packAndBuiltInRecipesPassExactCoreGate(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var loaded = manager.byKey(RECIPE_ID).orElseThrow();
        var resolver = MekanismGasToGasRecipeResolver.createAudited();
        require(loaded.getClass() == CentrifugingIRecipe.class
                        && loaded.getType() == resolver.supportedRecipeType()
                        && loaded.getType() == MekanismRecipeType.CENTRIFUGING.get()
                        && loaded.getSerializer() == ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID)
                        && manager.getAllRecipesFor(MekanismRecipeType.CENTRIFUGING.get()).contains(loaded),
                "pack centrifuging recipe escaped its exact RecipeManager Type");
        var hydrogen = gas("hydrogen", 2);
        var oxygen = gas("oxygen", 17);
        var valid = context(helper, MekanismKey.of(hydrogen), 2, MekanismKey.of(oxygen), 17);
        var candidate = MekanismRecipeAdapter.createBundled().resolve(loaded, valid).orElseThrow();
        require(candidate.recipeTypeId().equals(TYPE_ID)
                        && candidate.processFamily().equals(TYPE_ID)
                        && candidate.machineProfile().equals(id("mekanism", "isotopic_centrifuge"))
                        && candidate.inputsPerOperation().equals(Map.of(MekanismKey.of(hydrogen), 2L))
                        && candidate.outputsPerOperation().equals(Map.of(MekanismKey.of(oxygen), 17L)),
                "centrifuging candidate changed the audited Gas quantities or metadata");
        var result = ProcessModelValidator.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), valid);
        require(result.diagnostic() == ValidationDiagnostic.VALID
                        && result.model().orElseThrow().recipeId().equals(RECIPE_ID)
                        && result.model().orElseThrow().inputsPerOperation().equals(candidate.inputsPerOperation())
                        && result.model().orElseThrow().outputsPerOperation().equals(candidate.outputsPerOperation()),
                "Core did not verify the active pack centrifuging recipe");

        var builtIn = manager.byKey(id("mekanism", "processing/uranium/fissile_fuel")).orElseThrow();
        var builtInContext = context(helper, MekanismKey.of(gas("uranium_hexafluoride", 1)), 1,
                MekanismKey.of(gas("fissile_fuel", 1)), 1);
        var builtInCandidate = resolver.resolve(builtIn, builtInContext).orElseThrow();
        require(ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                        builtInCandidate), builtInContext).diagnostic() == ValidationDiagnostic.VALID,
                "built-in centrifuging recipe did not pass Core");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wrongPatternsAndUnknownShapesFailClosed(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var loaded = manager.byKey(RECIPE_ID).orElseThrow();
        var resolver = MekanismGasToGasRecipeResolver.createAudited();
        var hydrogen = MekanismKey.of(gas("hydrogen", 2));
        var oxygen = MekanismKey.of(gas("oxygen", 17));
        var valid = context(helper, hydrogen, 2, oxygen, 17);
        var candidate = resolver.resolve(loaded, valid).orElseThrow();
        var wrongInput = context(helper, MekanismKey.of(gas("oxygen", 2)), 2, oxygen, 17);
        var wrongInputAmount = context(helper, hydrogen, 3, oxygen, 17);
        var wrongOutput = context(helper, hydrogen, 2, oxygen, 18);
        var wrongOutputType = context(helper, hydrogen, 2, hydrogen, 17);
        var wrongForm = context(helper, MekanismKey.of(
                MekanismAPI.infuseTypeRegistry().getValue(id("mekanism", "carbon")).getStack(2)), 2, oxygen, 17);
        require(resolver.resolve(loaded, wrongInput).isEmpty()
                        && resolver.resolve(loaded, wrongInputAmount).isEmpty()
                        && resolver.resolve(loaded, wrongOutput).isEmpty()
                        && resolver.resolve(loaded, wrongOutputType).isEmpty()
                        && resolver.resolve(loaded, wrongForm).isEmpty()
                        && ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate),
                                wrongInputAmount).diagnostic() == ValidationDiagnostic.INPUT_MISMATCH
                        && ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate),
                                wrongOutput).diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH,
                "centrifuging accepted a wrong Pattern quantity, Gas type, or Chemical form");

        var original = (CentrifugingIRecipe) loaded;
        Recipe<?> subclass = new CentrifugingIRecipe(RECIPE_ID, original.getInput(),
                original.getOutput(gas("hydrogen", 2))) { };
        require(resolver.resolve(subclass, valid).isEmpty(), "unknown centrifuging subclass was accepted");
        Recipe<?> arrayRecipe = recipe(id("pack_test", "centrifuging_array"),
                "[{\"amount\":2,\"gas\":\"mekanism:hydrogen\"},"
                        + "{\"amount\":2,\"gas\":\"mekanism:oxygen\"}]", 17);
        require(resolver.resolve(arrayRecipe, valid).isEmpty(), "Gas input array was accepted");
        Recipe<?> tagRecipe = recipe(id("pack_test", "centrifuging_tag"),
                "{\"amount\":2,\"tag\":\"pack_test:missing_gas_tag\"}", 17);
        require(resolver.resolve(tagRecipe, valid).isEmpty(), "unverified Gas tag was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_centrifuging_recipe_reload", timeoutTicks = 200)
    public static void replacementAndRemovalUseCurrentRecipeManager(GameTestHelper helper) {
        var resolver = MekanismGasToGasRecipeResolver.createAudited();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var oldRecipe = manager.byKey(RECIPE_ID).orElseThrow();
        var oldPattern = context(helper, MekanismKey.of(gas("hydrogen", 2)), 2,
                MekanismKey.of(gas("oxygen", 17)), 17);
        require(resolver.resolve(oldRecipe, oldPattern).isPresent(), "pack recipe was not initially resolvable");
        try {
            Recipe<?> normalizedSingleton = recipe(RECIPE_ID,
                    "[{\"amount\":2,\"gas\":\"mekanism:hydrogen\"}]", 17);
            replace(manager, original, normalizedSingleton);
            var singleton = (CentrifugingIRecipe) normalizedSingleton;
            var direct = (CentrifugingIRecipe) oldRecipe;
            require(singleton.getInput().getClass() == direct.getInput().getClass()
                            && singleton.getInput().serialize().equals(direct.getInput().serialize()),
                    "Mekanism no longer normalizes singleton Gas arrays to direct single-Gas semantics");
            var singletonCandidate = resolver.resolve(normalizedSingleton, oldPattern).orElseThrow();
            require(ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0,
                            singletonCandidate), oldPattern).diagnostic() == ValidationDiagnostic.VALID,
                    "normalized singleton Gas array did not pass active RecipeManager/Core validation");

            Recipe<?> replacement = recipe(RECIPE_ID,
                    "{\"amount\":3,\"gas\":\"mekanism:hydrogen\"}", 19);
            replace(manager, original, replacement);
            var active = manager.byKey(RECIPE_ID).orElseThrow();
            require(active == replacement && resolver.resolve(active, oldPattern).isEmpty(),
                    "replaced centrifuging recipe retained old quantities");
            var updated = context(helper, MekanismKey.of(gas("hydrogen", 3)), 3,
                    MekanismKey.of(gas("oxygen", 19)), 19);
            var candidate = resolver.resolve(active, updated).orElseThrow();
            require(ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate),
                            updated).diagnostic() == ValidationDiagnostic.VALID,
                    "replacement centrifuging recipe was not revalidated");
            replace(manager, original, null);
            require(manager.byKey(RECIPE_ID).isEmpty()
                            && ProcessModelValidator.validate(
                                    new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), updated)
                                    .diagnostic() == ValidationDiagnostic.NO_RECIPE,
                    "removed centrifuging recipe remained authoritative");
        } finally {
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    private static PatternContext context(GameTestHelper helper, MekanismKey input, long inputAmount,
            MekanismKey output, long outputAmount) {
        require(input != null && output != null, "Chemical key is unavailable");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, inputAmount)},
                new GenericStack[]{new GenericStack(output, outputAmount)}), helper.getLevel());
        require(pattern != null, "centrifuging Processing Pattern did not decode");
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static mekanism.api.chemical.gas.GasStack gas(String name, long amount) {
        return MekanismAPI.gasRegistry().getValue(id("mekanism", name)).getStack(amount);
    }

    private static Recipe<?> recipe(ResourceLocation id, String inputJson, long outputAmount) {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "centrifuging serializer is unavailable");
        String json = "{\"input\":" + inputJson + ",\"output\":{\"amount\":"
                + outputAmount + ",\"gas\":\"mekanism:oxygen\"}}";
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
