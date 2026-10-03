package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonParser;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ProcessModelValidator;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.recipes.RotaryRecipe;
import mekanism.common.recipe.impl.RotaryIRecipe;
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

/** Exact active-recipe direction and fail-closed gates for rotary. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalRotaryResolverGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "rotary");
    private static final ResourceLocation PACK_ID = id("pack_test", "rotary");
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);
    private static final AEFluidKey LAVA = AEFluidKey.of(Fluids.LAVA);

    private ChemicalRotaryResolverGameTests() {
    }

    @GameTest(template = "empty")
    public static void activeDualAndSingleDirectionRecipesPassCore(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var adapter = MekanismRecipeAdapter.createBundled();
        var dual = manager.byKey(PACK_ID).orElseThrow();
        var type = ForgeRegistries.RECIPE_TYPES.getValue(TYPE_ID);
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(dual.getClass() == RotaryIRecipe.class && dual.getType() == type
                        && dual.getSerializer() == serializer && ((RotaryRecipe) dual).hasFluidToGas()
                        && ((RotaryRecipe) dual).hasGasToFluid()
                        && adapter.supportedRecipeTypes().contains(type),
                "active rotary Type/serializer/concrete class or dual direction changed");
        var fluid = context(helper, WATER, 7, gas("hydrogen"), 11);
        var gas = context(helper, gas("oxygen"), 13, LAVA, 17);
        ResolvedProcess fluidCandidate = adapter.resolve(dual, fluid).orElseThrow();
        ResolvedProcess gasCandidate = adapter.resolve(dual, gas).orElseThrow();
        require(fluidCandidate.processShape().equals(ProcessShapeId.of(
                        AppliedCausality.MOD_ID, "fluid_to_chemical"))
                        && fluidCandidate.inputsPerOperation().equals(Map.of(WATER, 7L))
                        && fluidCandidate.outputsPerOperation().equals(Map.of(gas("hydrogen"), 11L))
                        && gasCandidate.processShape().equals(ProcessShapeId.of(
                                AppliedCausality.MOD_ID, "chemical_to_fluid"))
                        && gasCandidate.inputsPerOperation().equals(Map.of(gas("oxygen"), 13L))
                        && gasCandidate.outputsPerOperation().equals(Map.of(LAVA, 17L))
                        && fluidCandidate.machineProfile().equals(id("mekanism", "rotary_condensentrator"))
                        && gasCandidate.machineProfile().equals(fluidCandidate.machineProfile())
                        && valid(fluidCandidate, fluid) && valid(gasCandidate, gas),
                "dual-direction rotary did not produce two separate Core-verified material maps");

        var fluidOnly = manager.byKey(id("pack_test", "rotary_fluid_only")).orElseThrow();
        var gasOnly = manager.byKey(id("pack_test", "rotary_gas_only")).orElseThrow();
        var fluidOnlyPattern = context(helper, WATER, 3, gas("hydrogen"), 5);
        var gasOnlyPattern = context(helper, gas("oxygen"), 4, LAVA, 6);
        require(valid(adapter.resolve(fluidOnly, fluidOnlyPattern).orElseThrow(), fluidOnlyPattern)
                        && valid(adapter.resolve(gasOnly, gasOnlyPattern).orElseThrow(), gasOnlyPattern)
                        && adapter.resolve(fluidOnly, gas).isEmpty()
                        && adapter.resolve(gasOnly, fluid).isEmpty(),
                "single-direction rotary was not gated by its declared direction");

        var builtIn = manager.byKey(id("mekanism", "rotary/water_vapor")).orElseThrow();
        var builtInFluid = context(helper, WATER, 1, gas("water_vapor"), 1);
        var builtInGas = context(helper, gas("water_vapor"), 1, WATER, 1);
        require(valid(adapter.resolve(builtIn, builtInFluid).orElseThrow(), builtInFluid)
                        && valid(adapter.resolve(builtIn, builtInGas).orElseThrow(), builtInGas)
                        && adapter.resolve(builtIn,
                                context(helper, LAVA, 1, gas("water_vapor"), 1)).isEmpty(),
                "built-in tagged Water rotary directions did not pass Core");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wrongAmountsShapesAndUnknownIngredientsReject(GameTestHelper helper) {
        var resolver = MekanismRotaryRecipeResolver.createAudited();
        var recipe = helper.getLevel().getRecipeManager().byKey(PACK_ID).orElseThrow();
        var fluid = context(helper, WATER, 7, gas("hydrogen"), 11);
        var gas = context(helper, gas("oxygen"), 13, LAVA, 17);
        var fluidCandidate = resolver.resolve(recipe, fluid).orElseThrow();
        var gasCandidate = resolver.resolve(recipe, gas).orElseThrow();
        require(resolver.resolve(recipe, context(helper, WATER, 8, gas("hydrogen"), 11)).isEmpty()
                        && resolver.resolve(recipe, context(helper, WATER, 7, gas("hydrogen"), 12)).isEmpty()
                        && resolver.resolve(recipe, context(helper, gas("oxygen"), 14, LAVA, 17)).isEmpty()
                        && resolver.resolve(recipe, context(helper, gas("oxygen"), 13, LAVA, 18)).isEmpty()
                        && resolver.resolve(recipe, context(helper, WATER, 7, LAVA, 17)).isEmpty()
                        && resolver.resolve(recipe, context(helper, gas("oxygen"), 13,
                                gas("hydrogen"), 11)).isEmpty()
                        && ProcessModelValidator.validate(new ProcessCandidate(
                                MekanismRecipeAdapter.ID, 0, fluidCandidate),
                                context(helper, WATER, 8, gas("hydrogen"), 11))
                                .diagnostic() == ValidationDiagnostic.INPUT_MISMATCH
                        && ProcessModelValidator.validate(new ProcessCandidate(
                                MekanismRecipeAdapter.ID, 0, gasCandidate),
                                context(helper, gas("oxygen"), 13, LAVA, 18))
                                .diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH,
                "wrong rotary direction, amount, or key gained authority");
        var both = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(WATER, 7), new GenericStack(gas("oxygen"), 13)},
                new GenericStack[]{new GenericStack(gas("hydrogen"), 11),
                        new GenericStack(LAVA, 17)}), helper.getLevel());
        require(both != null && resolver.resolve(recipe, new PatternContext(helper.getLevel(), both)).isEmpty(),
                "one rotary Pattern selected both directions");
        var original = (RotaryIRecipe) recipe;
        Recipe<?> subclass = new RotaryIRecipe(PACK_ID,
                original.getFluidInput(), original.getGasInput(),
                original.getGasOutputDefinition().get(0),
                original.getFluidOutputDefinition().get(0)) { };
        require(resolver.resolve(subclass, fluid).isEmpty(),
                "unknown rotary subclass was accepted");
        var alternatives = recipe(id("pack_test", "rotary_fluid_alternatives"),
                "\"fluidInput\":[{\"amount\":7,\"fluid\":\"minecraft:water\"},"
                        + "{\"amount\":7,\"fluid\":\"minecraft:lava\"}],"
                        + "\"gasOutput\":{\"gas\":\"mekanism:hydrogen\",\"amount\":11}");
        var taggedGas = recipe(id("pack_test", "rotary_gas_tag"),
                "\"gasInput\":{\"amount\":13,\"tag\":\"mekanism:water_vapor\"},"
                        + "\"fluidOutput\":{\"fluid\":\"minecraft:lava\",\"amount\":17}");
        var taggedInput = MekanismAPI.gasRegistry()
                .getValue(id("mekanism", "water_vapor")).getStack(13);
        require(!((RotaryRecipe) taggedGas).isIncomplete()
                        && ((RotaryRecipe) taggedGas).getGasInput().test(taggedInput)
                        && ((RotaryRecipe) taggedGas).test(taggedInput),
                "valid Mekanism Gas tag did not reach the rotary resolver gate");
        require(resolver.resolve(alternatives, fluid).isEmpty()
                        && resolver.resolve(taggedGas,
                                context(helper, gas("water_vapor"), 13, LAVA, 17)).isEmpty(),
                "unsupported rotary ingredient shape was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_rotary_recipe_reload", timeoutTicks = 200)
    public static void replacementAndRemovalRevalidateCore(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var resolver = MekanismRotaryRecipeResolver.createAudited();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var fluid = context(helper, WATER, 7, gas("hydrogen"), 11);
        var gas = context(helper, gas("oxygen"), 13, LAVA, 17);
        var oldFluid = resolver.resolve(manager.byKey(PACK_ID).orElseThrow(), fluid).orElseThrow();
        var oldGas = resolver.resolve(manager.byKey(PACK_ID).orElseThrow(), gas).orElseThrow();
        try {
            var changed = recipe(PACK_ID,
                    "\"fluidInput\":{\"amount\":8,\"fluid\":\"minecraft:water\"},"
                            + "\"gasOutput\":{\"gas\":\"mekanism:hydrogen\",\"amount\":12},"
                            + "\"gasInput\":{\"amount\":14,\"gas\":\"mekanism:oxygen\"},"
                            + "\"fluidOutput\":{\"fluid\":\"minecraft:lava\",\"amount\":18}");
            replace(manager, original, changed);
            require(resolver.resolve(changed, fluid).isEmpty()
                            && resolver.resolve(changed, gas).isEmpty()
                            && valid(resolver.resolve(changed,
                                    context(helper, WATER, 8, gas("hydrogen"), 12)).orElseThrow(),
                                    context(helper, WATER, 8, gas("hydrogen"), 12))
                            && valid(resolver.resolve(changed,
                                    context(helper, gas("oxygen"), 14, LAVA, 18)).orElseThrow(),
                                    context(helper, gas("oxygen"), 14, LAVA, 18)),
                    "rotary replacement kept stale amounts or lost new direction");
            replace(manager, original, null);
            require(ProcessModelValidator.validate(new ProcessCandidate(
                            MekanismRecipeAdapter.ID, 0, oldFluid), fluid)
                            .diagnostic() == ValidationDiagnostic.NO_RECIPE
                            && ProcessModelValidator.validate(new ProcessCandidate(
                                    MekanismRecipeAdapter.ID, 0, oldGas), gas)
                                    .diagnostic() == ValidationDiagnostic.NO_RECIPE,
                    "removed rotary recipe retained Core authority");
        } finally {
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    private static PatternContext context(GameTestHelper helper, AEKey input, long inputAmount,
            AEKey output, long outputAmount) {
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, inputAmount)},
                new GenericStack[]{new GenericStack(output, outputAmount)}), helper.getLevel());
        require(pattern != null, "rotary Processing Pattern did not decode");
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static Recipe<?> recipe(ResourceLocation id, String body) {
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "rotary serializer is unavailable");
        return serializer.fromJson(id, JsonParser.parseString("{" + body + "}").getAsJsonObject());
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original, Recipe<?> replacement) {
        List<Recipe<?>> next = new ArrayList<>(original);
        next.removeIf(recipe -> recipe.getId().equals(PACK_ID));
        if (replacement != null) {
            next.add(replacement);
        }
        manager.replaceRecipes(next);
    }

    private static MekanismKey gas(String path) {
        return MekanismKey.of(MekanismAPI.gasRegistry()
                .getValue(id("mekanism", path)).getStack(1));
    }

    private static boolean valid(ResolvedProcess candidate, PatternContext context) {
        return ProcessModelValidator.validate(new ProcessCandidate(
                MekanismRecipeAdapter.ID, 0, candidate), context)
                .diagnostic() == ValidationDiagnostic.VALID;
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
