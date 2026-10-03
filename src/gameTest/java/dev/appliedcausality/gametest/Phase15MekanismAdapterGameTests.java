package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBinding;
import dev.appliedcausality.internal.binding.ProviderBindingStore;
import dev.appliedcausality.internal.compat.AppliedMekanisticsCompatibility;
import dev.appliedcausality.internal.compat.MekanismCompatibility;
import dev.appliedcausality.internal.registry.ProcessModelResolver;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import mekanism.common.recipe.impl.CrushingIRecipe;
import mekanism.common.recipe.impl.EnrichingIRecipe;
import mekanism.common.recipe.impl.SmeltingIRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.List;

/** Exact Mekanism 10.4.16 RecipeManager-to-Core integration coverage. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase15MekanismAdapterGameTests {
    private Phase15MekanismAdapterGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void chemicalTypesRequireAppliedMekanistics(GameTestHelper helper) {
        for (String name : List.of("oxidizing", "gas_conversion", "infusion_conversion",
                "pigment_extracting", "metallurgic_infusing", "centrifuging", "crystallizing")) {
            var type = ForgeRegistries.RECIPE_TYPES.getValue(mekanismId(name));
            helper.assertTrue(type != null, "Mekanism " + name + " Type was not registered");
            boolean enabled = MekanismRecipeAdapter.createBundled().supportedRecipeTypes().contains(type);
            helper.assertTrue(enabled == AppliedMekanisticsCompatibility.isLoadedAndCompatible(),
                    name + " Type did not follow the optional Chemical-key addon gate");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void allItemToItemRecipesPromoteThroughBundledRegistryAndCore(GameTestHelper helper) {
        helper.assertTrue(
                MekanismCompatibility.isLoadedAndCompatible(),
                "exact Mekanism 10.4.16 runtime must be detected");

        List<ExpectedRecipe> recipes = List.of(
                new ExpectedRecipe(
                        "phase15_enriching", Items.WARPED_FUNGUS, Items.BEACON,
                        EnrichingIRecipe.class, "mekanism", "enriching", "enrichment_chamber"),
                new ExpectedRecipe(
                        "phase15_crushing", Items.CRIMSON_FUNGUS, Items.DRAGON_EGG,
                        CrushingIRecipe.class, "mekanism", "crushing", "crusher"),
                new ExpectedRecipe(
                        "phase15_smelting", Items.CHORUS_FLOWER, Items.HEART_OF_THE_SEA,
                        SmeltingIRecipe.class, "mekanism", "smelting", "energized_smelter"));
        for (ExpectedRecipe expected : recipes) {
            verifyPromotedRecipe(helper, expected);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void crushingIgnoresInvalidVanillaSmeltingCandidate(GameTestHelper helper) {
        ResourceLocation recipeId = mekanismId("crushing/cobblestone_to_gravel");
        var encoded = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.COBBLESTONE), 1)},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.GRAVEL), 1)});
        var pattern = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        helper.assertTrue(pattern != null, "AE2 processing pattern did not decode: " + recipeId);

        var result = ProcessModelResolver.resolve(new PatternContext(helper.getLevel(), pattern));
        helper.assertTrue(
                result.diagnostic() == ValidationDiagnostic.VALID,
                "invalid Vanilla Smelting candidate polluted Crushing selection: " + result.diagnostic());
        VerifiedProcessModel model = result.model().orElse(null);
        helper.assertTrue(model != null, "Core did not produce VerifiedProcessModel: " + recipeId);
        helper.assertTrue(model.recipeId().equals(recipeId), "wrong authoritative recipe selected: " + recipeId);
        helper.assertTrue(
                model.adapterId().equals(id("mekanism")),
                "Mekanism Crushing adapter was not selected: " + recipeId);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void legacyBindingAliasesRequireExactTypeAndRevalidateRecipe(GameTestHelper helper) {
        var aliases = List.of("mekanism_enriching", "mekanism_crushing",
                "mekanism_energized_smelting");
        var recipes = List.of("phase15_enriching", "phase15_crushing", "phase15_smelting");
        var inputs = List.of(Items.WARPED_FUNGUS, Items.CRIMSON_FUNGUS, Items.CHORUS_FLOWER);
        var outputs = List.of(Items.BEACON, Items.DRAGON_EGG, Items.HEART_OF_THE_SEA);
        for (int i = 0; i < aliases.size(); i++) {
            var encoded = PatternDetailsHelper.encodeProcessingPattern(
                    new GenericStack[]{new GenericStack(AEItemKey.of(inputs.get(i)), 2)},
                    new GenericStack[]{new GenericStack(AEItemKey.of(outputs.get(i)), 3)});
            var pattern = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
            helper.assertTrue(pattern != null, "legacy pattern failed to decode");
            var context = new PatternContext(helper.getLevel(), pattern);
            var binding = new ProcessBinding(0, PatternFingerprint.fromDefinition(encoded),
                    id(aliases.get(i)), id(recipes.get(i)));
            var result = ProcessModelResolver.resolveBound(context, binding);
            helper.assertTrue(result.diagnostic() == ValidationDiagnostic.VALID,
                    "valid legacy binding rejected: " + aliases.get(i) + " " + result.diagnostic());
            helper.assertTrue(result.model().orElseThrow().adapterId().equals(id("mekanism")),
                    "legacy binding did not resolve canonically");
            var migrated = ProcessModelResolver.migrateLegacyBinding(context, binding).orElseThrow();
            helper.assertTrue(migrated.adapterId().equals(id("mekanism")),
                    "validated legacy identity was not migrated");
            var store = ProviderBindingStore.empty(1);
            store.put(migrated);
            helper.assertTrue(ProviderBindingStore.load(store.save(), 1)
                            .matching(0, binding.fingerprint()).orElseThrow()
                            .adapterId().equals(id("mekanism")),
                    "next save did not retain canonical binding identity");
            var wrongAlias = new ProcessBinding(0, binding.fingerprint(),
                    id(aliases.get((i + 1) % aliases.size())), binding.recipeId());
            helper.assertTrue(ProcessModelResolver.resolveBound(context, wrongAlias).model().isEmpty(),
                    "wrong alias/type pair was accepted");
            var unknown = new ProcessBinding(0, binding.fingerprint(), id("mekanism_unknown"), binding.recipeId());
            helper.assertTrue(ProcessModelResolver.resolveBound(context, unknown).model().isEmpty(),
                    "unknown adapter identity was accepted");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void providerMigratesPersistedLegacyBindingAcrossReloadAndSave(GameTestHelper helper) {
        var encoded = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.WARPED_FUNGUS), 2)},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.BEACON), 3)});
        var fingerprint = PatternFingerprint.fromDefinition(encoded);
        var oldBinding = new ProcessBinding(0, fingerprint,
                id("mekanism_enriching"), id("phase15_enriching"));

        var firstPos = new BlockPos(1, 1, 1);
        helper.setBlock(firstPos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var source = (SingularPatternProviderBlockEntity) helper.getBlockEntity(firstPos);
        source.getInternalInventory().setItemDirect(0, encoded);
        var oldSave = new CompoundTag();
        source.saveAdditional(oldSave);
        var oldStore = ProviderBindingStore.empty(SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
        oldStore.put(oldBinding);
        oldSave.getCompound("applied_causality").put("bindings", oldStore.save());

        var secondPos = new BlockPos(2, 1, 1);
        helper.setBlock(secondPos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var loaded = (SingularPatternProviderBlockEntity) helper.getBlockEntity(secondPos);
        loaded.loadTag(oldSave);
        loaded.rebuildAfterRecipeReload();
        helper.assertTrue(loaded.getAvailablePatterns().size() == 1,
                "Provider did not revalidate the saved legacy Binding");
        var migratedSave = new CompoundTag();
        loaded.saveAdditional(migratedSave);
        var migrated = ProviderBindingStore.load(
                migratedSave.getCompound("applied_causality").getCompound("bindings"),
                SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT)
                .matching(0, fingerprint).orElseThrow();
        helper.assertTrue(migrated.adapterId().equals(id("mekanism")),
                "Provider save retained the legacy adapter ID");
        helper.assertTrue(migrated.recipeId().equals(oldBinding.recipeId()),
                "Provider migration changed recipe identity");

        var thirdPos = new BlockPos(3, 1, 1);
        helper.setBlock(thirdPos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var reloaded = (SingularPatternProviderBlockEntity) helper.getBlockEntity(thirdPos);
        reloaded.loadTag(migratedSave);
        reloaded.rebuildAfterRecipeReload();
        helper.assertTrue(reloaded.getAvailablePatterns().size() == 1,
                "Canonical Binding did not survive Provider reload");
        var finalSave = new CompoundTag();
        reloaded.saveAdditional(finalSave);
        helper.assertTrue(ProviderBindingStore.load(
                        finalSave.getCompound("applied_causality").getCompound("bindings"),
                        SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT)
                        .matching(0, fingerprint).orElseThrow().adapterId().equals(id("mekanism")),
                "Canonical Binding was not retained by the second save");
        helper.succeed();
    }

    private static void verifyPromotedRecipe(GameTestHelper helper, ExpectedRecipe expected) {
        ResourceLocation recipeId = id(expected.recipePath());
        var serverRecipe = helper.getLevel().getRecipeManager().byKey(recipeId).orElse(null);
        helper.assertTrue(serverRecipe != null, "test recipe was not loaded by RecipeManager: " + recipeId);
        helper.assertTrue(
                serverRecipe.getClass() == expected.recipeClass(),
                "RecipeManager did not construct the exact Mekanism recipe class: " + recipeId);

        var encoded = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(expected.input()), 2)},
                new GenericStack[]{new GenericStack(AEItemKey.of(expected.output()), 3)});
        var pattern = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        helper.assertTrue(pattern != null, "AE2 processing pattern did not decode: " + recipeId);

        var result = ProcessModelResolver.resolve(new PatternContext(helper.getLevel(), pattern));
        helper.assertTrue(
                result.diagnostic() == ValidationDiagnostic.VALID,
                "bundled/indexed/Core resolution rejected " + recipeId + ": " + result.diagnostic());
        VerifiedProcessModel model = result.model().orElse(null);
        helper.assertTrue(model != null, "Core did not produce VerifiedProcessModel: " + recipeId);
        helper.assertTrue(model.recipeId().equals(recipeId), "wrong authoritative recipe selected: " + recipeId);
        helper.assertTrue(
                model.adapterId().equals(id(expected.adapterPath())),
                "bundled adapter was not selected: " + recipeId);
        helper.assertTrue(
                model.recipeTypeId().equals(mekanismId(expected.recipeTypePath())),
                "wrong recipe type reached Core: " + recipeId);
        helper.assertTrue(
                model.machineProfile().equals(mekanismId(expected.machinePath())),
                "wrong machine profile reached Core: " + recipeId);
        helper.assertTrue(
                model.inputsPerOperation().get(AEItemKey.of(expected.input())) == 2L,
                "wrong verified input amount: " + recipeId);
        helper.assertTrue(
                model.outputsPerOperation().get(AEItemKey.of(expected.output())) == 3L,
                "wrong verified output amount: " + recipeId);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AppliedCausality.MOD_ID, path);
    }

    private static ResourceLocation mekanismId(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }

    private record ExpectedRecipe(
            String recipePath,
            net.minecraft.world.item.Item input,
            net.minecraft.world.item.Item output,
            Class<?> recipeClass,
            String adapterPath,
            String recipeTypePath,
            String machinePath) {
    }
}
