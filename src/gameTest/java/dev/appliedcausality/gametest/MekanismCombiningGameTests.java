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
import dev.appliedcausality.internal.registry.ProcessModelResolver;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import mekanism.common.recipe.impl.CombinerIRecipe;
import mekanism.common.recipe.impl.SawmillIRecipe;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Live server authority and AE2 dispatch coverage for exact two-item combining. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MekanismCombiningGameTests {
    private static final AEItemKey MAIN = AEItemKey.of(Items.SPORE_BLOSSOM);
    private static final AEItemKey EXTRA = AEItemKey.of(Items.GLOW_BERRIES);
    private static final AEItemKey OUTPUT = AEItemKey.of(Items.NETHER_STAR);

    private MekanismCombiningGameTests() {
    }

    @GameTest(template = "empty")
    public static void distinctRepeatedAndOverlappingInputsFailClosed(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        helper.assertTrue(manager.byKey(packId("combining")).orElseThrow().getClass() == CombinerIRecipe.class,
                "pack combining recipe did not use Mekanism's concrete class");
        helper.assertTrue(manager.byKey(packId("combining_repeated")).orElseThrow().getClass()
                        == CombinerIRecipe.class, "repeated-key recipe did not load");
        helper.assertTrue(manager.byKey(packId("combining_overlap")).orElseThrow().getClass()
                        == CombinerIRecipe.class, "overlapping-tag recipe did not load");
        helper.assertTrue(manager.byKey(packId("chance_sawing")).orElseThrow().getClass()
                        == SawmillIRecipe.class, "chance sawmill recipe did not load");
        helper.assertTrue(manager.byKey(packId("safe_sawing")).orElseThrow().getClass()
                        == SawmillIRecipe.class, "fixed-output sawmill recipe did not load");

        var distinct = definition(new GenericStack[]{stack(MAIN, 2), stack(EXTRA, 3)}, OUTPUT, 4);
        var result = ProcessModelResolver.resolve(context(helper, distinct));
        helper.assertTrue(result.diagnostic() == ValidationDiagnostic.VALID,
                "two-role combining did not verify: " + result.diagnostic());
        var model = result.model().orElseThrow();
        helper.assertTrue(model.recipeId().equals(packId("combining"))
                        && model.adapterId().equals(acId("mekanism"))
                        && model.recipeTypeId().equals(mekanismId("combining"))
                        && model.machineProfile().equals(mekanismId("combiner"))
                        && model.inputsPerOperation().get(MAIN) == 2L
                        && model.inputsPerOperation().get(EXTRA) == 3L
                        && model.outputsPerOperation().get(OUTPUT) == 4L,
                "combining role quantities were not normalized exactly");
        var reversed = definition(new GenericStack[]{stack(EXTRA, 3), stack(MAIN, 2)}, OUTPUT, 4);
        helper.assertTrue(ProcessModelResolver.resolve(context(helper, reversed)).model().isPresent(),
                "Pattern slot order was incorrectly used as Mekanism input roles");

        var repeated = definition(new GenericStack[]{
                stack(AEItemKey.of(Items.BLAZE_POWDER), 2),
                stack(AEItemKey.of(Items.BLAZE_POWDER), 3)}, AEItemKey.of(Items.EMERALD), 2);
        var repeatedResult = ProcessModelResolver.resolve(context(helper, repeated));
        helper.assertTrue(repeatedResult.diagnostic() == ValidationDiagnostic.VALID
                        && repeatedResult.model().orElseThrow().inputsPerOperation()
                        .get(AEItemKey.of(Items.BLAZE_POWDER)) == 5L,
                "repeated input key was not aggregated exactly");

        var overlap = new ProcessBinding(0, PatternFingerprint.fromDefinition(distinct),
                acId("mekanism"), packId("combining_overlap"));
        helper.assertTrue(ProcessModelResolver.resolveBound(context(helper, distinct), overlap).model().isEmpty(),
                "overlapping tag roles were accepted without unique assignment");
        assertRejected(helper, new GenericStack[]{stack(MAIN, 2), stack(EXTRA, 2)}, OUTPUT, 4);
        assertRejected(helper, new GenericStack[]{stack(MAIN, 2), stack(EXTRA, 3)},
                AEItemKey.of(Items.BEACON), 4);
        assertRejected(helper, new GenericStack[]{stack(MAIN, 2), stack(AEItemKey.of(Items.DIRT), 3)}, OUTPUT, 4);
        assertRejected(helper, new GenericStack[]{stack(AEItemKey.of(Items.BLAZE_ROD), 1)},
                AEItemKey.of(Items.BEACON), 2);
        var safeSawing = ProcessModelResolver.resolve(context(helper, definition(
                new GenericStack[]{stack(AEItemKey.of(Items.PRISMARINE_CRYSTALS), 2)},
                AEItemKey.of(Items.DIAMOND), 3)));
        helper.assertTrue(safeSawing.diagnostic() == ValidationDiagnostic.VALID
                        && safeSawing.model().orElseThrow().recipeId().equals(packId("safe_sawing")),
                "zero-chance, no-secondary sawmill recipe did not verify");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void standardAe2CombiningUsesOneDispatchAndExactOwnership(GameTestHelper helper) throws Exception {
        for (long operations : new long[]{1, 1000}) {
            var f = new Phase11StandardAe2GameTests.Fixture(helper);
            f.addProcessingProvider(BlockPos.ZERO,
                    new GenericStack[]{stack(MAIN, 2), stack(EXTRA, 3)},
                    new GenericStack[]{stack(OUTPUT, 4)});
            f.storage.items.set(MAIN, Math.multiplyExact(2, operations));
            f.storage.items.set(EXTRA, Math.multiplyExact(3, operations));
            var plan = f.calculate(OUTPUT, Math.multiplyExact(4, operations));
            helper.assertTrue(!plan.simulation(), "stock AE2 could not plan combining");
            f.submit(plan, f.requester);
            var job = f.job();
            var task = job.bulk$tasks().iterator().next();
            helper.assertTrue(task.remaining() == operations, "planner changed operation count");
            helper.assertTrue(f.execute() == 1 && task.remaining() == 0,
                    "combining did not finish in one scheduler operation");
            helper.assertTrue(f.requester.accepted == 4 * operations
                            && f.storage.items.get(MAIN) == 0 && f.storage.items.get(EXTRA) == 0
                            && job.bulk$outputEscrow().isEmpty() && f.cpu.bulk$getJob() == null,
                    "combining output or inputs retained duplicate ownership");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void repeatedKeyCombiningConsumesAggregateExactly(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        AEItemKey key = AEItemKey.of(Items.BLAZE_POWDER);
        AEItemKey output = AEItemKey.of(Items.EMERALD);
        f.addProcessingProvider(BlockPos.ZERO,
                new GenericStack[]{stack(key, 2), stack(key, 3)},
                new GenericStack[]{stack(output, 2)});
        f.storage.items.set(key, 5);
        f.submit(f.calculate(output, 2), f.requester);
        helper.assertTrue(f.execute() == 1 && f.storage.items.get(key) == 0
                        && f.requester.accepted == 2 && f.cpu.bulk$getJob() == null,
                "repeated-key combining did not consume five items in one dispatch");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void deterministicSawingUsesOneBulkDispatch(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        AEItemKey input = AEItemKey.of(Items.PRISMARINE_CRYSTALS);
        AEItemKey output = AEItemKey.of(Items.DIAMOND);
        f.addProcessingProvider(BlockPos.ZERO,
                new GenericStack[]{stack(input, 2)},
                new GenericStack[]{stack(output, 3)});
        f.storage.items.set(input, 200);
        f.submit(f.calculate(output, 300), f.requester);
        helper.assertTrue(f.execute() == 1 && f.storage.items.get(input) == 0
                        && f.requester.accepted == 300 && f.cpu.bulk$getJob() == null,
                "fixed-output sawmill recipe did not deliver exact Bulk output");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void repeatedKeyAmountsAboveIntegerMaxStayExact(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        ResourceLocation id = packId("combining_large");
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS.getValue(mekanismId("combining"));
        helper.assertTrue(serializer != null, "combining serializer missing");
        String json = "{\"mainInput\":{\"amount\":2147483647,"
                + "\"ingredient\":{\"item\":\"minecraft:blaze_powder\"}},"
                + "\"extraInput\":{\"amount\":2147483647,"
                + "\"ingredient\":{\"item\":\"minecraft:blaze_powder\"}},"
                + "\"output\":{\"count\":2,\"item\":\"minecraft:emerald\"}}";
        List<Recipe<?>> withLarge = new ArrayList<>(original);
        withLarge.add(serializer.fromJson(id, JsonParser.parseString(json).getAsJsonObject()));
        try {
            manager.replaceRecipes(withLarge);
            AEItemKey key = AEItemKey.of(Items.BLAZE_POWDER);
            var pattern = definition(new GenericStack[]{stack(key, Integer.MAX_VALUE),
                    stack(key, Integer.MAX_VALUE)}, AEItemKey.of(Items.EMERALD), 2);
            var result = ProcessModelResolver.resolve(context(helper, pattern));
            helper.assertTrue(result.diagnostic() == ValidationDiagnostic.VALID
                            && result.model().orElseThrow().recipeId().equals(id)
                            && result.model().orElseThrow().inputsPerOperation().get(key)
                            == 2L * Integer.MAX_VALUE,
                    "two int-sized recipe roles were not summed as an exact long");
        } finally {
            manager.replaceRecipes(original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void combiningEscrowSurvivesRestartAndCancellation(GameTestHelper helper) throws Exception {
        var f = new Phase11StandardAe2GameTests.Fixture(helper);
        f.addProcessingProvider(BlockPos.ZERO,
                new GenericStack[]{stack(MAIN, 2), stack(EXTRA, 3)},
                new GenericStack[]{stack(OUTPUT, 4)});
        f.storage.items.set(MAIN, 20);
        f.storage.items.set(EXTRA, 30);
        f.requester.limit = 2;
        f.submit(f.calculate(OUTPUT, 40), f.requester);
        helper.assertTrue(f.execute() == 1, "combining did not dispatch");
        var job = f.job();
        helper.assertTrue(job.bulk$outputEscrow().get(OUTPUT) == 38
                        && job.bulk$getRemainingFinalAmount() == 38
                        && job.bulk$getWaitingFor(OUTPUT) == 0,
                "partial acceptance failed exact escrow accounting");
        var saved = new CompoundTag();
        f.logic.writeToNBT(saved);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        helper.assertTrue(restored.job().bulk$outputEscrow().get(OUTPUT) == 38,
                "combining escrow was not restored");
        restored.storage.acceptInsert = false;
        restored.logic.cancel();
        helper.assertTrue(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(OUTPUT) == 38,
                "cancellation lost or duplicated combining output");
        f.requester.limit = Long.MAX_VALUE;
        f.logic.tickCraftingLogic(f.energy, f.service);
        helper.assertTrue(f.cpu.bulk$getJob() == null && f.requester.accepted == 40,
                "original job did not deliver its exact remaining combining output");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changedCombiningRecipeRevalidatesPersistedBinding(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        var definition = definition(new GenericStack[]{stack(MAIN, 2), stack(EXTRA, 3)}, OUTPUT, 4);
        var pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getBlockEntity(pos);
        provider.getInternalInventory().setItemDirect(0, definition);
        provider.rebuildAfterRecipeReload();
        helper.assertTrue(provider.trySetProcessBinding(0, acId("mekanism"), packId("combining")),
                "combining Binding was not created");
        CompoundTag saved = new CompoundTag();
        provider.saveAdditional(saved);
        var bound = new ProcessBinding(0, PatternFingerprint.fromDefinition(definition),
                acId("mekanism"), packId("combining"));
        try {
            replace(manager, original, replacement(4, "minecraft:diamond"));
            provider.invalidateForRecipeReload();
            provider.rebuildAfterRecipeReload();
            helper.assertTrue(provider.getAvailablePatterns().isEmpty()
                            && ProcessModelResolver.resolveBound(context(helper, definition), bound).model().isEmpty(),
                    "changed output kept the old combining Binding executable");

            replace(manager, original, null);
            provider.invalidateForRecipeReload();
            provider.rebuildAfterRecipeReload();
            helper.assertTrue(provider.getAvailablePatterns().isEmpty()
                            && ProcessModelResolver.resolveBound(context(helper, definition), bound).model().isEmpty(),
                    "removed combining recipe remained executable");

            var restoredPos = new BlockPos(2, 1, 1);
            helper.setBlock(restoredPos, AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
            var restored = (SingularPatternProviderBlockEntity) helper.getBlockEntity(restoredPos);
            restored.loadTag(saved);
            restored.rebuildAfterRecipeReload();
            helper.assertTrue(restored.getAvailablePatterns().isEmpty(),
                    "saved Binding trusted a removed combining recipe");

            replace(manager, original, replacement(4, "minecraft:nether_star"));
            provider.invalidateForRecipeReload();
            provider.rebuildAfterRecipeReload();
            helper.assertTrue(provider.getAvailablePatterns().isEmpty(),
                    "changed main quantity kept the old combining Pattern active");

            manager.replaceRecipes(original);
            provider.invalidateForRecipeReload();
            provider.rebuildAfterRecipeReload();
            restored.invalidateForRecipeReload();
            restored.rebuildAfterRecipeReload();
            helper.assertTrue(provider.getAvailablePatterns().size() == 1
                            && restored.getAvailablePatterns().size() == 1,
                    "restored combining recipe did not revalidate saved Binding");
        } finally {
            manager.replaceRecipes(original);
            provider.invalidateForRecipeReload();
            provider.rebuildAfterRecipeReload();
        }
        helper.succeed();
    }

    private static Recipe<?> replacement(int mainAmount, String output) {
        RecipeSerializer<?> serializer = ForgeRegistries.RECIPE_SERIALIZERS
                .getValue(mekanismId("combining"));
        if (serializer == null) {
            throw new IllegalStateException("combining serializer missing");
        }
        String json = "{\"mainInput\":{\"amount\":" + mainAmount
                + ",\"ingredient\":{\"item\":\"minecraft:spore_blossom\"}},"
                + "\"extraInput\":{\"amount\":3,\"ingredient\":{\"item\":\"minecraft:glow_berries\"}},"
                + "\"output\":{\"count\":4,\"item\":\"" + output + "\"}}";
        return serializer.fromJson(packId("combining"), JsonParser.parseString(json).getAsJsonObject());
    }

    private static void replace(RecipeManager manager, List<Recipe<?>> original, Recipe<?> replacement) {
        List<Recipe<?>> next = new ArrayList<>(original);
        next.removeIf(recipe -> recipe.getId().equals(packId("combining")));
        if (replacement != null) {
            next.add(replacement);
        }
        manager.replaceRecipes(next);
    }

    private static void assertRejected(GameTestHelper helper, GenericStack[] inputs,
            AEItemKey output, long amount) {
        var result = ProcessModelResolver.resolve(context(helper, definition(inputs, output, amount)));
        helper.assertTrue(result.model().isEmpty(), "invalid Item-only pattern was verified");
    }

    private static PatternContext context(GameTestHelper helper, net.minecraft.world.item.ItemStack definition) {
        var pattern = PatternDetailsHelper.decodePattern(definition, helper.getLevel());
        if (pattern == null) {
            throw new AssertionError("processing pattern did not decode");
        }
        return new PatternContext(helper.getLevel(), pattern);
    }

    private static net.minecraft.world.item.ItemStack definition(GenericStack[] inputs,
            AEItemKey output, long amount) {
        return PatternDetailsHelper.encodeProcessingPattern(inputs, new GenericStack[]{stack(output, amount)});
    }

    private static GenericStack stack(AEItemKey key, long amount) {
        return new GenericStack(key, amount);
    }

    private static ResourceLocation packId(String path) {
        return ResourceLocation.fromNamespaceAndPath("pack_test", path);
    }

    private static ResourceLocation mekanismId(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }

    private static ResourceLocation acId(String path) {
        return ResourceLocation.fromNamespaceAndPath(AppliedCausality.MOD_ID, path);
    }
}
