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
import mekanism.common.recipe.impl.FluidSlurryToSlurryIRecipe;
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

/** Exact washing candidates require both independent material roles. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalWashingResolverGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "washing");
    private static final ResourceLocation PACK_ID = id("pack_test", "washing");
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);

    private ChemicalWashingResolverGameTests() {
    }

    @GameTest(template = "empty")
    public static void packAndBuiltInRecipesPassCore(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var dirty = slurry("dirty_iron");
        var clean = slurry("clean_iron");
        var context = context(helper, WATER, 7, dirty, 11, clean, 13);
        var candidate = MekanismRecipeAdapter.createBundled().resolve(
                manager.byKey(PACK_ID).orElseThrow(), context).orElseThrow();
        require(candidate.recipeTypeId().equals(TYPE_ID)
                        && candidate.processFamily().equals(TYPE_ID)
                        && candidate.machineProfile().equals(id("mekanism", "chemical_washer"))
                        && candidate.inputsPerOperation().equals(Map.of(WATER, 7L, dirty, 11L))
                        && candidate.outputsPerOperation().equals(Map.of(clean, 13L))
                        && valid(candidate, context),
                "pack Fluid+Slurry washing recipe did not pass Core");
        var builtIn = manager.byKey(id("mekanism", "processing/iron/slurry/clean"))
                .orElseThrow();
        var builtInContext = context(helper, WATER, 5, dirty, 1, clean, 1);
        require(valid(MekanismWashingRecipeResolver.createAudited()
                        .resolve(builtIn, builtInContext).orElseThrow(), builtInContext),
                "built-in tagged Water washing recipe did not pass Core");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wrongPatternsAndUnknownIngredientsFailClosed(GameTestHelper helper) {
        var resolver = MekanismWashingRecipeResolver.createAudited();
        var loaded = helper.getLevel().getRecipeManager().byKey(PACK_ID).orElseThrow();
        var dirty = slurry("dirty_iron");
        var clean = slurry("clean_iron");
        var validContext = context(helper, WATER, 7, dirty, 11, clean, 13);
        var candidate = resolver.resolve(loaded, validContext).orElseThrow();
        var wrongFluidAmount = context(helper, WATER, 8, dirty, 11, clean, 13);
        var wrongSlurryAmount = context(helper, WATER, 7, dirty, 12, clean, 13);
        var wrongOutput = context(helper, WATER, 7, dirty, 11, clean, 14);
        var wrongForm = MekanismKey.of(MekanismAPI.gasRegistry()
                .getValue(id("mekanism", "hydrogen")).getStack(1));
        var wrongFormContext = context(helper, WATER, 7, wrongForm, 11, clean, 13);
        require(resolver.resolve(loaded, wrongFluidAmount).isEmpty()
                        && resolver.resolve(loaded, wrongSlurryAmount).isEmpty()
                        && resolver.resolve(loaded, wrongOutput).isEmpty()
                        && resolver.resolve(loaded, wrongFormContext).isEmpty()
                        && ProcessModelValidator.validate(new ProcessCandidate(
                                MekanismRecipeAdapter.ID, 0, candidate), wrongFluidAmount)
                                .diagnostic() == ValidationDiagnostic.INPUT_MISMATCH
                        && ProcessModelValidator.validate(new ProcessCandidate(
                                MekanismRecipeAdapter.ID, 0, candidate), wrongOutput)
                                .diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH,
                "wrong washing Pattern gained authority");
        var original = (FluidSlurryToSlurryIRecipe) loaded;
        Recipe<?> subclass = new FluidSlurryToSlurryIRecipe(PACK_ID,
                original.getFluidInput(), original.getChemicalInput(),
                original.getOutput(new net.minecraftforge.fluids.FluidStack(Fluids.WATER, 7),
                        MekanismAPI.slurryRegistry().getValue(id("mekanism", "dirty_iron"))
                                .getStack(11))) { };
        require(resolver.resolve(subclass, validContext).isEmpty(),
                "unknown washing subclass was accepted");
        Recipe<?> fluidAlternatives = recipe(id("pack_test", "washing_fluid_alternatives"),
                "[{\"amount\":7,\"fluid\":\"minecraft:water\"},"
                        + "{\"amount\":7,\"fluid\":\"minecraft:lava\"}]",
                "{\"amount\":11,\"slurry\":\"mekanism:dirty_iron\"}", 13);
        Recipe<?> slurryTag = recipe(id("pack_test", "washing_slurry_tag"),
                "{\"amount\":7,\"fluid\":\"minecraft:water\"}",
                "{\"amount\":11,\"tag\":\"pack_test:unknown_slurry\"}", 13);
        require(resolver.resolve(fluidAlternatives, validContext).isEmpty()
                        && resolver.resolve(slurryTag, validContext).isEmpty(),
                "unsupported washing ingredient shape was accepted");
        var builtIn = helper.getLevel().getRecipeManager()
                .byKey(id("mekanism", "processing/iron/slurry/clean")).orElseThrow();
        var lava = context(helper, AEFluidKey.of(Fluids.LAVA), 5, dirty, 1, clean, 1);
        require(resolver.resolve(builtIn, lava).isEmpty(),
                "Fluid outside the built-in Water tag was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_washing_recipe_reload", timeoutTicks = 200)
    public static void replacementAndRemovalRevalidateCore(GameTestHelper helper) {
        var resolver = MekanismWashingRecipeResolver.createAudited();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var old = manager.byKey(PACK_ID).orElseThrow();
        var dirty = slurry("dirty_iron");
        var clean = slurry("clean_iron");
        var context = context(helper, WATER, 7, dirty, 11, clean, 13);
        var oldCandidate = resolver.resolve(old, context).orElseThrow();
        try {
            Recipe<?> singletonArrays = recipe(PACK_ID,
                    "[{\"amount\":7,\"fluid\":\"minecraft:water\"}]",
                    "[{\"amount\":11,\"slurry\":\"mekanism:dirty_iron\"}]", 13);
            replace(manager, original, singletonArrays);
            require(valid(resolver.resolve(singletonArrays, context).orElseThrow(), context),
                    "normalized singleton Fluid/Slurry arrays changed semantics");
            Recipe<?> changed = recipe(PACK_ID,
                    "{\"amount\":8,\"fluid\":\"minecraft:water\"}",
                    "{\"amount\":12,\"slurry\":\"mekanism:dirty_iron\"}", 17);
            replace(manager, original, changed);
            require(resolver.resolve(changed, context).isEmpty(),
                    "changed washing recipe kept stale candidate");
            var updated = context(helper, WATER, 8, dirty, 12, clean, 17);
            require(valid(resolver.resolve(changed, updated).orElseThrow(), updated),
                    "changed washing recipe did not pass Core");
            replace(manager, original, null);
            require(manager.byKey(PACK_ID).isEmpty()
                            && ProcessModelValidator.validate(new ProcessCandidate(
                                    MekanismRecipeAdapter.ID, 0, oldCandidate), context)
                                    .diagnostic() == ValidationDiagnostic.NO_RECIPE,
                    "removed washing recipe retained Core authority");
        } finally {
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    private static boolean valid(dev.appliedcausality.api.process.ResolvedProcess candidate,
            PatternContext context) {
        return ProcessModelValidator.validate(new ProcessCandidate(
                MekanismRecipeAdapter.ID, 0, candidate), context)
                .diagnostic() == ValidationDiagnostic.VALID;
    }

    private static PatternContext context(GameTestHelper helper, AEFluidKey fluid,
            long fluidAmount, MekanismKey slurryInput, long slurryAmount,
            MekanismKey output, long outputAmount) {
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(slurryInput, slurryAmount),
                        new GenericStack(fluid, fluidAmount)},
                new GenericStack[]{new GenericStack(output, outputAmount)}), helper.getLevel());
        require(pattern != null, "washing Pattern did not decode");
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static MekanismKey slurry(String path) {
        return MekanismKey.of(MekanismAPI.slurryRegistry()
                .getValue(id("mekanism", path)).getStack(1));
    }

    private static Recipe<?> recipe(ResourceLocation id, String fluid, String slurry, long output) {
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "washing serializer is unavailable");
        String json = "{\"fluidInput\":" + fluid + ",\"slurryInput\":" + slurry
                + ",\"output\":{\"amount\":" + output
                + ",\"slurry\":\"mekanism:clean_iron\"}}";
        return serializer.fromJson(id, JsonParser.parseString(json).getAsJsonObject());
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original,
            Recipe<?> replacement) {
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
