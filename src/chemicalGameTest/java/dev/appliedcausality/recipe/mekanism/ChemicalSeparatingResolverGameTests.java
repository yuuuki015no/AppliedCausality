package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonParser;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ProcessModelValidator;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.common.recipe.impl.ElectrolysisIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks the direct Fluid / paired Gas candidate against the active RecipeManager. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalSeparatingResolverGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "separating");
    private static final ResourceLocation PACK_ID = id("pack_test", "separating");

    private ChemicalSeparatingResolverGameTests() {
    }

    @GameTest(template = "empty")
    public static void builtInTaggedWaterAndBrinePassCore(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var resolver = MekanismSeparatingRecipeResolver.createAudited();
        var water = manager.byKey(id("mekanism", "separator/water")).orElseThrow();
        var waterContext = context(helper, 2, stack(gas("hydrogen"), 2), stack(gas("oxygen"), 1));
        var waterCandidate = MekanismRecipeAdapter.createBundled()
                .resolve(water, waterContext).orElseThrow();
        require(valid(waterCandidate, waterContext)
                        && waterCandidate.inputsPerOperation().equals(Map.of(AEFluidKey.of(Fluids.WATER), 2L)),
                "built-in tagged Water separating recipe did not pass Core");
        var brine = ForgeRegistries.FLUIDS.getValue(id("mekanism", "brine"));
        require(brine != null, "Mekanism Brine Fluid is unavailable");
        var brineKey = AEFluidKey.of(brine);
        var brineContext = context(helper, brineKey, 10,
                stack(gas("sodium"), 1), stack(gas("chlorine"), 1));
        var brineCandidate = resolver.resolve(manager.byKey(id("mekanism", "separator/brine"))
                .orElseThrow(), brineContext).orElseThrow();
        require(valid(brineCandidate, brineContext)
                        && brineCandidate.inputsPerOperation().equals(Map.of(brineKey, 10L)),
                "built-in tagged Brine separating recipe did not pass Core");
        var wrongFluid = context(helper, AEFluidKey.of(Fluids.LAVA), 2,
                stack(gas("hydrogen"), 2), stack(gas("oxygen"), 1));
        require(resolver.resolve(water, wrongFluid).isEmpty(),
                "Fluid outside the built-in Water tag was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void packAndSameGasPassCore(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var resolver = MekanismSeparatingRecipeResolver.createAudited();
        var hydrogen = gas("hydrogen");
        var oxygen = gas("oxygen");
        var context = context(helper, 3, stack(hydrogen, 5), stack(oxygen, 7));
        var candidate = MekanismRecipeAdapter.createBundled()
                .resolve(manager.byKey(PACK_ID).orElseThrow(), context).orElseThrow();
        require(candidate.recipeTypeId().equals(TYPE_ID)
                        && candidate.processFamily().equals(TYPE_ID)
                        && candidate.machineProfile().equals(id("mekanism", "electrolytic_separator"))
                        && candidate.inputsPerOperation().equals(Map.of(AEFluidKey.of(Fluids.WATER), 3L))
                        && candidate.outputsPerOperation().equals(Map.of(hydrogen, 5L, oxygen, 7L))
                        && valid(candidate, context),
                "pack Fluid-to-two-Gas recipe failed Core validation");
        var same = manager.byKey(id("pack_test", "separating_same_gas")).orElseThrow();
        var sameContext = context(helper, 4, stack(hydrogen, 5), stack(hydrogen, 7));
        var sameCandidate = resolver.resolve(same, sameContext).orElseThrow();
        require(sameCandidate.outputsPerOperation().equals(Map.of(hydrogen, 12L))
                        && valid(sameCandidate, sameContext),
                "same-Gas output quantities were not checked and aggregated before Core");
        var higherEnergy = recipe(id("pack_test", "separating_higher_energy"),
                "{\"amount\":3,\"fluid\":\"minecraft:water\"}",
                "{\"amount\":5,\"gas\":\"mekanism:hydrogen\"}",
                "{\"amount\":7,\"gas\":\"mekanism:oxygen\"}", 7);
        var higherEnergyCandidate = resolver.resolve(higherEnergy, context).orElseThrow();
        require(higherEnergyCandidate.inputsPerOperation().equals(candidate.inputsPerOperation())
                        && higherEnergyCandidate.outputsPerOperation().equals(candidate.outputsPerOperation()),
                "energyMultiplier changed separating material quantities");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wrongPatternsAndUncertainRecipesFailClosed(GameTestHelper helper) {
        var resolver = MekanismSeparatingRecipeResolver.createAudited();
        var loaded = helper.getLevel().getRecipeManager().byKey(PACK_ID).orElseThrow();
        var hydrogen = gas("hydrogen");
        var oxygen = gas("oxygen");
        var validContext = context(helper, 3, stack(hydrogen, 5), stack(oxygen, 7));
        var candidate = resolver.resolve(loaded, validContext).orElseThrow();
        var wrongInput = context(helper, 4, stack(hydrogen, 5), stack(oxygen, 7));
        var wrongOutput = context(helper, 3, stack(hydrogen, 5), stack(oxygen, 8));
        var missingOutput = context(helper, 3, stack(hydrogen, 5));
        var wrongForm = MekanismKey.of(MekanismAPI.pigmentRegistry()
                .getValue(id("mekanism", "red")).getStack(7));
        var wrongFormContext = context(helper, 3, stack(hydrogen, 5), stack(wrongForm, 7));
        require(resolver.resolve(loaded, wrongInput).isEmpty()
                        && resolver.resolve(loaded, wrongOutput).isEmpty()
                        && resolver.resolve(loaded, missingOutput).isEmpty()
                        && resolver.resolve(loaded, wrongFormContext).isEmpty()
                        && ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate),
                                wrongInput).diagnostic() == ValidationDiagnostic.INPUT_MISMATCH
                        && ProcessModelValidator.validate(new ProcessCandidate(MekanismRecipeAdapter.ID, 0, candidate),
                                wrongOutput).diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH,
                "wrong separating Pattern gained authority");

        var original = (ElectrolysisIRecipe) loaded;
        var output = original.getOutput(new net.minecraftforge.fluids.FluidStack(Fluids.WATER, 3));
        Recipe<?> subclass = new ElectrolysisIRecipe(PACK_ID, original.getInput(),
                original.getEnergyMultiplier(), output.left(), output.right()) { };
        require(resolver.resolve(subclass, validContext).isEmpty(), "unknown recipe subclass was accepted");
        Recipe<?> alternatives = recipe(id("pack_test", "separating_alternatives"),
                "[{\"amount\":3,\"fluid\":\"minecraft:water\"},"
                        + "{\"amount\":3,\"fluid\":\"minecraft:lava\"}]",
                "{\"amount\":5,\"gas\":\"mekanism:hydrogen\"}",
                "{\"amount\":7,\"gas\":\"mekanism:oxygen\"}");
        require(resolver.resolve(alternatives, validContext).isEmpty(),
                "multi-fluid alternative was accepted");
        Recipe<?> overflow = recipe(id("pack_test", "separating_overflow"),
                "{\"amount\":3,\"fluid\":\"minecraft:water\"}",
                "{\"amount\":" + Long.MAX_VALUE + ",\"gas\":\"mekanism:hydrogen\"}",
                "{\"amount\":1,\"gas\":\"mekanism:hydrogen\"}");
        require(resolver.resolve(overflow, validContext).isEmpty(),
                "same-Gas output overflow was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_separating_recipe_reload", timeoutTicks = 200)
    public static void replacementAndRemovalRevalidateCore(GameTestHelper helper) {
        var resolver = MekanismSeparatingRecipeResolver.createAudited();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var old = manager.byKey(PACK_ID).orElseThrow();
        var context = context(helper, 3, stack(gas("hydrogen"), 5), stack(gas("oxygen"), 7));
        var oldCandidate = resolver.resolve(old, context).orElseThrow();
        try {
            Recipe<?> singletonArray = recipe(PACK_ID,
                    "[{\"amount\":3,\"fluid\":\"minecraft:water\"}]",
                    "{\"amount\":5,\"gas\":\"mekanism:hydrogen\"}",
                    "{\"amount\":7,\"gas\":\"mekanism:oxygen\"}");
            replace(manager, original, singletonArray);
            var normalized = (ElectrolysisIRecipe) singletonArray;
            require(normalized.getInput().getClass() == ((ElectrolysisIRecipe) old).getInput().getClass()
                            && normalized.getInput().serialize().equals(((ElectrolysisIRecipe) old).getInput().serialize())
                            && valid(resolver.resolve(singletonArray, context).orElseThrow(), context),
                    "Mekanism-normalized singleton Fluid array changed recipe semantics");
            Recipe<?> changed = recipe(PACK_ID,
                    "{\"amount\":4,\"fluid\":\"minecraft:water\"}",
                    "{\"amount\":9,\"gas\":\"mekanism:hydrogen\"}",
                    "{\"amount\":11,\"gas\":\"mekanism:oxygen\"}");
            replace(manager, original, changed);
            require(resolver.resolve(changed, context).isEmpty(), "changed recipe kept stale candidate");
            var updated = context(helper, 4, stack(gas("hydrogen"), 9), stack(gas("oxygen"), 11));
            require(valid(resolver.resolve(changed, updated).orElseThrow(), updated),
                    "changed Fluid recipe did not pass Core");
            replace(manager, original, null);
            require(manager.byKey(PACK_ID).isEmpty()
                            && ProcessModelValidator.validate(new ProcessCandidate(
                                    MekanismRecipeAdapter.ID, 0, oldCandidate), context)
                                    .diagnostic() == ValidationDiagnostic.NO_RECIPE,
                    "removed separating recipe retained Core authority");
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

    private static PatternContext context(GameTestHelper helper, long inputAmount, GenericStack... outputs) {
        return context(helper, AEFluidKey.of(Fluids.WATER), inputAmount, outputs);
    }

    private static PatternContext context(GameTestHelper helper, AEFluidKey fluid,
            long inputAmount, GenericStack... outputs) {
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(fluid, inputAmount)},
                outputs), helper.getLevel());
        require(pattern != null, "separating Pattern did not decode");
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static GenericStack stack(MekanismKey key, long amount) {
        require(key != null, "Gas key is unavailable");
        return new GenericStack(key, amount);
    }

    private static MekanismKey gas(String path) {
        return MekanismKey.of(MekanismAPI.gasRegistry().getValue(id("mekanism", path)).getStack(1));
    }

    private static Recipe<?> recipe(ResourceLocation id, String input, String left, String right) {
        return recipe(id, input, left, right, 1);
    }

    private static Recipe<?> recipe(ResourceLocation id, String input, String left,
            String right, int energyMultiplier) {
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "separating serializer is unavailable");
        String json = "{\"input\":" + input + ",\"leftGasOutput\":" + left
                + ",\"rightGasOutput\":" + right
                + ",\"energyMultiplier\":" + energyMultiplier + "}";
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
