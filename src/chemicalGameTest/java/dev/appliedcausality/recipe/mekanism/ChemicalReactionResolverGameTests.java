package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonParser;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ResolvedProcess;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ProcessModelValidator;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.common.recipe.impl.PressurizedReactionIRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Active RecipeManager/Core gates for all three fixed reaction output forms. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalReactionResolverGameTests {
    private static final ResourceLocation TYPE_ID = id("mekanism", "reaction");
    private static final ResourceLocation PACK_ID = id("pack_test", "reaction");
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);
    private static final AEItemKey COBBLE = AEItemKey.of(Items.COBBLESTONE);
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);

    private ChemicalReactionResolverGameTests() {
    }

    @GameTest(template = "empty")
    public static void dualAndOptionalOutputsPassCore(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        var resolver = MekanismRecipeAdapter.createBundled();
        var dual = context(helper, COBBLE, 2, WATER, 7, gas("hydrogen"), 11,
                new GenericStack(IRON, 13), new GenericStack(gas("oxygen"), 17));
        ResolvedProcess candidate = resolver.resolve(manager.byKey(PACK_ID).orElseThrow(), dual)
                .orElseThrow();
        require(candidate.recipeTypeId().equals(TYPE_ID)
                        && candidate.machineProfile().equals(id("mekanism", "pressurized_reaction_chamber"))
                        && candidate.inputsPerOperation().equals(Map.of(
                                COBBLE, 2L, WATER, 7L, gas("hydrogen"), 11L))
                        && candidate.outputsPerOperation().equals(Map.of(
                                IRON, 13L, gas("oxygen"), 17L))
                        && valid(candidate, dual),
                "dual-output reaction did not pass Core");
        var itemOnly = context(helper, COBBLE, 2, WATER, 7, gas("oxygen"), 11,
                new GenericStack(IRON, 13));
        var gasOnly = context(helper, COBBLE, 2, WATER, 7, gas("oxygen"), 11,
                new GenericStack(gas("hydrogen"), 17));
        require(valid(resolver.resolve(manager.byKey(id("pack_test", "reaction_item_only"))
                        .orElseThrow(), itemOnly).orElseThrow(), itemOnly)
                        && valid(resolver.resolve(manager.byKey(id("pack_test", "reaction_gas_only"))
                        .orElseThrow(), gasOnly).orElseThrow(), gasOnly),
                "single-output reaction form did not pass Core");
        var builtIn = manager.byKey(id("mekanism", "reaction/substrate/water_ethene"))
                .orElseThrow();
        var substrate = AEItemKey.of(ForgeRegistries.ITEMS.getValue(id("mekanism", "substrate")));
        var builtInContext = context(helper, substrate, 1, WATER, 200, gas("ethene"), 100,
                new GenericStack(substrate, 8), new GenericStack(gas("oxygen"), 10));
        require(valid(resolver.resolve(builtIn, builtInContext).orElseThrow(), builtInContext),
                "built-in tagged Water reaction did not pass Core");
        var taggedItem = manager.byKey(id("mekanism", "reaction/coal_gasification/coals"))
                .orElseThrow();
        var sulfur = AEItemKey.of(ForgeRegistries.ITEMS.getValue(id("mekanism", "dust_sulfur")));
        var taggedItemContext = context(helper, AEItemKey.of(Items.COAL), 1, WATER, 100,
                gas("oxygen"), 100, new GenericStack(sulfur, 1),
                new GenericStack(gas("hydrogen"), 100));
        require(valid(resolver.resolve(taggedItem, taggedItemContext).orElseThrow(),
                taggedItemContext), "built-in tagged Item reaction did not pass Core");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mismatchesAndUnknownSemanticsFailClosed(GameTestHelper helper) {
        var resolver = MekanismReactionRecipeResolver.createAudited();
        var loaded = helper.getLevel().getRecipeManager().byKey(PACK_ID).orElseThrow();
        var correct = context(helper, COBBLE, 2, WATER, 7, gas("hydrogen"), 11,
                new GenericStack(IRON, 13), new GenericStack(gas("oxygen"), 17));
        var candidate = resolver.resolve(loaded, correct).orElseThrow();
        var wrongItem = context(helper, COBBLE, 3, WATER, 7, gas("hydrogen"), 11,
                new GenericStack(IRON, 13), new GenericStack(gas("oxygen"), 17));
        var wrongFluid = context(helper, COBBLE, 2, WATER, 8, gas("hydrogen"), 11,
                new GenericStack(IRON, 13), new GenericStack(gas("oxygen"), 17));
        var wrongGas = context(helper, COBBLE, 2, WATER, 7, gas("hydrogen"), 12,
                new GenericStack(IRON, 13), new GenericStack(gas("oxygen"), 17));
        var wrongOutput = context(helper, COBBLE, 2, WATER, 7, gas("hydrogen"), 11,
                new GenericStack(IRON, 13), new GenericStack(gas("oxygen"), 18));
        var wrongItemOutput = context(helper, COBBLE, 2, WATER, 7, gas("hydrogen"), 11,
                new GenericStack(IRON, 14), new GenericStack(gas("oxygen"), 17));
        var wrongForm = MekanismKey.of(MekanismAPI.infuseTypeRegistry()
                .getValue(id("mekanism", "carbon")).getStack(1));
        var wrongFormContext = context(helper, COBBLE, 2, WATER, 7, wrongForm, 11,
                new GenericStack(IRON, 13), new GenericStack(gas("oxygen"), 17));
        require(resolver.resolve(loaded, wrongItem).isEmpty()
                        && resolver.resolve(loaded, wrongFluid).isEmpty()
                        && resolver.resolve(loaded, wrongGas).isEmpty()
                        && resolver.resolve(loaded, wrongOutput).isEmpty()
                        && resolver.resolve(loaded, wrongItemOutput).isEmpty()
                        && resolver.resolve(loaded, wrongFormContext).isEmpty()
                        && ProcessModelValidator.validate(new ProcessCandidate(
                                MekanismRecipeAdapter.ID, 0, candidate), wrongItem)
                                .diagnostic() == ValidationDiagnostic.INPUT_MISMATCH
                        && ProcessModelValidator.validate(new ProcessCandidate(
                                MekanismRecipeAdapter.ID, 0, candidate), wrongOutput)
                                .diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH,
                "wrong reaction Pattern gained authority");
        var original = (PressurizedReactionIRecipe) loaded;
        Recipe<?> subclass = new PressurizedReactionIRecipe(PACK_ID,
                original.getInputSolid(), original.getInputFluid(), original.getInputGas(),
                original.getEnergyRequired(), original.getDuration(),
                original.getOutputDefinition().get(0).item(),
                original.getOutputDefinition().get(0).gas()) { };
        require(resolver.resolve(subclass, correct).isEmpty(),
                "unknown reaction subclass was accepted");
        var alternatives = recipe(id("pack_test", "reaction_alternatives"),
                "{\"ingredient\":[{\"item\":\"minecraft:cobblestone\"},"
                        + "{\"item\":\"minecraft:stone\"}],\"amount\":2}",
                "{\"amount\":7,\"fluid\":\"minecraft:water\"}",
                "{\"amount\":11,\"gas\":\"mekanism:hydrogen\"}", 13, 17);
        var gasTag = recipe(id("pack_test", "reaction_gas_tag"),
                "{\"ingredient\":{\"item\":\"minecraft:cobblestone\"},\"amount\":2}",
                "{\"amount\":7,\"fluid\":\"minecraft:water\"}",
                "{\"amount\":11,\"tag\":\"pack_test:unknown_gas\"}", 13, 17);
        var fluidAlternatives = recipe(id("pack_test", "reaction_fluid_alternatives"),
                "{\"ingredient\":{\"item\":\"minecraft:cobblestone\"},\"amount\":2}",
                "[{\"amount\":7,\"fluid\":\"minecraft:water\"},"
                        + "{\"amount\":7,\"fluid\":\"minecraft:lava\"}]",
                "{\"amount\":11,\"gas\":\"mekanism:hydrogen\"}", 13, 17);
        require(resolver.resolve(alternatives, correct).isEmpty()
                        && resolver.resolve(gasTag, correct).isEmpty()
                        && resolver.resolve(fluidAlternatives, correct).isEmpty(),
                "unsupported reaction ingredient shape was accepted");
        var builtIn = helper.getLevel().getRecipeManager()
                .byKey(id("mekanism", "reaction/substrate/water_ethene")).orElseThrow();
        var substrate = AEItemKey.of(ForgeRegistries.ITEMS.getValue(id("mekanism", "substrate")));
        var lava = context(helper, substrate, 1, AEFluidKey.of(Fluids.LAVA), 200,
                gas("ethene"), 100, new GenericStack(substrate, 8),
                new GenericStack(gas("oxygen"), 10));
        require(resolver.resolve(builtIn, lava).isEmpty(),
                "Fluid outside the built-in Water tag was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "chemical_reaction_recipe_reload", timeoutTicks = 200)
    public static void replacementAndRemovalRevalidateCore(GameTestHelper helper) {
        var resolver = MekanismReactionRecipeResolver.createAudited();
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var context = context(helper, COBBLE, 2, WATER, 7, gas("hydrogen"), 11,
                new GenericStack(IRON, 13), new GenericStack(gas("oxygen"), 17));
        var old = resolver.resolve(manager.byKey(PACK_ID).orElseThrow(), context).orElseThrow();
        try {
            var changedCost = recipe(PACK_ID,
                    "{\"ingredient\":{\"item\":\"minecraft:cobblestone\"},\"amount\":2}",
                    "{\"amount\":7,\"fluid\":\"minecraft:water\"}",
                    "{\"amount\":11,\"gas\":\"mekanism:hydrogen\"}", 13, 17);
            replace(manager, original, changedCost);
            require(valid(resolver.resolve(changedCost, context).orElseThrow(), context),
                    "reaction energy/duration changed fixed materials");
            var changed = recipe(PACK_ID,
                    "{\"ingredient\":{\"item\":\"minecraft:cobblestone\"},\"amount\":3}",
                    "{\"amount\":8,\"fluid\":\"minecraft:water\"}",
                    "{\"amount\":12,\"gas\":\"mekanism:hydrogen\"}", 19, 23);
            replace(manager, original, changed);
            require(resolver.resolve(changed, context).isEmpty(),
                    "changed reaction recipe kept stale candidate");
            var updated = context(helper, COBBLE, 3, WATER, 8, gas("hydrogen"), 12,
                    new GenericStack(IRON, 19), new GenericStack(gas("oxygen"), 23));
            require(valid(resolver.resolve(changed, updated).orElseThrow(), updated),
                    "changed reaction recipe did not pass Core");
            replace(manager, original, null);
            require(manager.byKey(PACK_ID).isEmpty()
                            && ProcessModelValidator.validate(new ProcessCandidate(
                                    MekanismRecipeAdapter.ID, 0, old), context)
                                    .diagnostic() == ValidationDiagnostic.NO_RECIPE,
                    "removed reaction recipe retained Core authority");
        } finally {
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    private static PatternContext context(GameTestHelper helper, AEItemKey item, long itemAmount,
            AEFluidKey fluid, long fluidAmount, MekanismKey gas, long gasAmount,
            GenericStack... outputs) {
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(gas, gasAmount),
                        new GenericStack(item, itemAmount), new GenericStack(fluid, fluidAmount)},
                outputs), helper.getLevel());
        require(pattern != null, "reaction Pattern did not decode");
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static Recipe<?> recipe(ResourceLocation id, String item, String fluid, String gas,
            long itemOutput, long gasOutput) {
        var serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(TYPE_ID);
        require(serializer != null, "reaction serializer is unavailable");
        String json = "{\"itemInput\":" + item + ",\"fluidInput\":" + fluid
                + ",\"gasInput\":" + gas + ",\"duration\":30,\"itemOutput\":{\"item\":"
                + "\"minecraft:iron_ingot\",\"count\":" + itemOutput
                + "},\"gasOutput\":{\"gas\":\"mekanism:oxygen\",\"amount\":"
                + gasOutput + "}}";
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
