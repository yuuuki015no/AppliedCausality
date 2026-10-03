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
import mekanism.common.recipe.impl.ChemicalInfuserIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks the canonical two-Gas candidate against the active RecipeManager. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalInfusingResolverGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "chemical_infusing");
    private static final ResourceLocation PACK_ID = id("pack_test", "chemical_infusing");

    private ChemicalInfusingResolverGameTests() {
    }

    @GameTest(template = "empty")
    public static void packBuiltInAndSameGasPassCore(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var resolver = MekanismChemicalInfusingRecipeResolver.createAudited();
        var pack = manager.byKey(PACK_ID).orElseThrow();
        var h = key("hydrogen");
        var cl = key("chlorine");
        var o = key("oxygen");
        var context = context(helper, new GenericStack[]{stack(cl, 3), stack(h, 2)}, stack(o, 17));
        var candidate = MekanismRecipeAdapter.createBundled().resolve(pack, context).orElseThrow();
        require(candidate.recipeTypeId().equals(TYPE_ID)
                        && candidate.processFamily().equals(TYPE_ID)
                        && candidate.machineProfile().equals(id("mekanism", "chemical_infuser"))
                        && candidate.inputsPerOperation().equals(Map.of(h, 2L, cl, 3L))
                        && candidate.outputsPerOperation().equals(Map.of(o, 17L))
                        && valid(candidate, context), "pack two-Gas recipe failed exact Core validation");

        var same = manager.byKey(id("pack_test", "chemical_infusing_same_gas")).orElseThrow();
        var sameContext = context(helper, new GenericStack[]{stack(h, 2), stack(h, 3)},
                stack(key("water_vapor"), 7));
        var sameCandidate = resolver.resolve(same, sameContext).orElseThrow();
        require(sameCandidate.inputsPerOperation().equals(Map.of(h, 5L))
                        && valid(sameCandidate, sameContext),
                "same-Gas role amounts were not checked and aggregated before Core");

        var builtIn = manager.byKey(id("mekanism", "chemical_infusing/sulfur_trioxide")).orElseThrow();
        var builtInContext = context(helper,
                new GenericStack[]{stack(key("sulfur_dioxide"), 2), stack(o, 1)},
                stack(key("sulfur_trioxide"), 2));
        require(valid(resolver.resolve(builtIn, builtInContext).orElseThrow(), builtInContext),
                "built-in symmetric Gas recipe failed Core");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wrongPatternsAndIngredientShapesFailClosed(GameTestHelper helper) {
        var resolver = MekanismChemicalInfusingRecipeResolver.createAudited();
        var loaded = helper.getLevel().getRecipeManager().byKey(PACK_ID).orElseThrow();
        var h = key("hydrogen");
        var cl = key("chlorine");
        var o = key("oxygen");
        var valid = context(helper, new GenericStack[]{stack(h, 2), stack(cl, 3)}, stack(o, 17));
        var candidate = resolver.resolve(loaded, valid).orElseThrow();
        var wrongInput = context(helper, new GenericStack[]{stack(h, 3), stack(cl, 3)}, stack(o, 17));
        var missingInput = context(helper, new GenericStack[]{stack(h, 2)}, stack(o, 17));
        var wrongOutput = context(helper, new GenericStack[]{stack(h, 2), stack(cl, 3)}, stack(o, 18));
        var wrongForm = MekanismKey.of(MekanismAPI.infuseTypeRegistry()
                .getValue(id("mekanism", "carbon")).getStack(3));
        var formContext = context(helper, new GenericStack[]{stack(h, 2), stack(wrongForm, 3)}, stack(o, 17));
        require(resolver.resolve(loaded, wrongInput).isEmpty()
                        && resolver.resolve(loaded, missingInput).isEmpty()
                        && resolver.resolve(loaded, wrongOutput).isEmpty()
                        && resolver.resolve(loaded, formContext).isEmpty()
                        && ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate),
                                wrongInput).diagnostic() == ValidationDiagnostic.INPUT_MISMATCH
                        && ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate),
                                wrongOutput).diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH,
                "wrong two-Gas Pattern gained authority");

        var original = (ChemicalInfuserIRecipe) loaded;
        Recipe<?> subclass = new ChemicalInfuserIRecipe(PACK_ID, original.getLeftInput(),
                original.getRightInput(), original.getOutput(
                        MekanismAPI.gasRegistry().getValue(id("mekanism", "hydrogen")).getStack(2),
                        MekanismAPI.gasRegistry().getValue(id("mekanism", "chlorine")).getStack(3))) { };
        require(resolver.resolve(subclass, valid).isEmpty(), "unknown recipe subclass was accepted");
        Recipe<?> alternatives = recipe(id("pack_test", "chemical_infusing_alternatives"),
                "[{\"amount\":2,\"gas\":\"mekanism:hydrogen\"},"
                        + "{\"amount\":2,\"gas\":\"mekanism:chlorine\"}]",
                "{\"amount\":3,\"gas\":\"mekanism:chlorine\"}", 17);
        Recipe<?> tag = recipe(id("pack_test", "chemical_infusing_tag"),
                "{\"amount\":2,\"tag\":\"pack_test:unknown_gas\"}",
                "{\"amount\":3,\"gas\":\"mekanism:chlorine\"}", 17);
        require(resolver.resolve(alternatives, valid).isEmpty()
                        && resolver.resolve(tag, valid).isEmpty(),
                "ambiguous alternative or tag ingredient was accepted");
        Recipe<?> overflow = recipe(id("pack_test", "chemical_infusing_overflow"),
                "{\"amount\":" + Long.MAX_VALUE + ",\"gas\":\"mekanism:hydrogen\"}",
                "{\"amount\":1,\"gas\":\"mekanism:hydrogen\"}", 17);
        require(resolver.resolve(overflow, valid).isEmpty(),
                "same-Gas role amounts overflowed the checked input map");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_infusing_recipe_reload", timeoutTicks = 200)
    public static void replacementAndRemovalRevalidateCore(GameTestHelper helper) {
        var resolver = MekanismChemicalInfusingRecipeResolver.createAudited();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var old = manager.byKey(PACK_ID).orElseThrow();
        var oldContext = context(helper,
                new GenericStack[]{stack(key("hydrogen"), 2), stack(key("chlorine"), 3)},
                stack(key("oxygen"), 17));
        var oldCandidate = resolver.resolve(old, oldContext).orElseThrow();
        try {
            Recipe<?> singletonArray = recipe(PACK_ID,
                    "[{\"amount\":2,\"gas\":\"mekanism:hydrogen\"}]",
                    "{\"amount\":3,\"gas\":\"mekanism:chlorine\"}", 17);
            replace(manager, original, singletonArray);
            var direct = (ChemicalInfuserIRecipe) old;
            var normalized = (ChemicalInfuserIRecipe) singletonArray;
            require(normalized.getLeftInput().getClass() == direct.getLeftInput().getClass()
                            && normalized.getLeftInput().serialize().equals(direct.getLeftInput().serialize())
                            && valid(resolver.resolve(singletonArray, oldContext).orElseThrow(), oldContext),
                    "Mekanism-normalized singleton Gas array changed recipe semantics");
            Recipe<?> replacement = recipe(PACK_ID,
                    "{\"amount\":4,\"gas\":\"mekanism:hydrogen\"}",
                    "{\"amount\":5,\"gas\":\"mekanism:chlorine\"}", 19);
            replace(manager, original, replacement);
            require(resolver.resolve(replacement, oldContext).isEmpty(),
                    "changed recipe retained stale candidate");
            var updated = context(helper,
                    new GenericStack[]{stack(key("chlorine"), 5), stack(key("hydrogen"), 4)},
                    stack(key("oxygen"), 19));
            require(valid(resolver.resolve(replacement, updated).orElseThrow(), updated),
                    "replacement recipe did not pass current Core authority");
            replace(manager, original, null);
            require(manager.byKey(PACK_ID).isEmpty()
                            && ProcessModelValidator.validate(new ProcessCandidate(
                                    MekanismRecipeAdapter.ID, 0, oldCandidate), oldContext)
                                    .diagnostic() == ValidationDiagnostic.NO_RECIPE,
                    "removed recipe retained Core authority");
        } finally {
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    private static boolean valid(dev.appliedcausality.api.process.ResolvedProcess candidate,
            PatternContext context) {
        return ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate), context)
                .diagnostic() == ValidationDiagnostic.VALID;
    }

    private static PatternContext context(GameTestHelper helper, GenericStack[] inputs, GenericStack output) {
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                inputs, new GenericStack[]{output}), helper.getLevel());
        require(pattern != null, "two-Gas Pattern did not decode");
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static GenericStack stack(MekanismKey key, long amount) {
        require(key != null, "Gas key is unavailable");
        return new GenericStack(key, amount);
    }

    private static MekanismKey key(String path) {
        return MekanismKey.of(MekanismAPI.gasRegistry().getValue(id("mekanism", path)).getStack(1));
    }

    private static Recipe<?> recipe(ResourceLocation id, String left, String right, long output) {
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "chemical_infusing serializer is unavailable");
        String json = "{\"leftInput\":" + left + ",\"rightInput\":" + right
                + ",\"output\":{\"amount\":" + output + ",\"gas\":\"mekanism:oxygen\"}}";
        return serializer.fromJson(id, JsonParser.parseString(json).getAsJsonObject());
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
