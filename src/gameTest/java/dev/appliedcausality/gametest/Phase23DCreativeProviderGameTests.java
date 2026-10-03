package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.internal.config.ConfiguredRuleService;
import dev.appliedcausality.internal.provider.ProviderAuthorDiagnostics;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;

/** Live rule generation, AE2 dispatch, and block ownership for the Creative variant. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase23DCreativeProviderGameTests {
    private Phase23DCreativeProviderGameTests() {
    }

    @GameTest(template = "empty", batch = "phase23d_creative")
    public static void creativeBypassesOnlyUnlockAndUsesStandardBulkPath(GameTestHelper helper) throws Exception {
        var original = ConfiguredRuleService.snapshot(helper.getLevel().getServer());
        var deny = original.withDefaultAllow(false);
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        try {
            Phase23CInstallerGameTests.reloadRules(helper, deny);
            var normal = new SingularPatternProviderBlockEntity(BlockPos.ZERO,
                    AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get().defaultBlockState());
            var creative = new SingularPatternProviderBlockEntity(new BlockPos(1, 0, 0),
                    AppliedCausalityContent.CREATIVE_SINGULAR_PATTERN_PROVIDER.get().defaultBlockState());
            normal.setLevel(helper.getLevel());
            creative.setLevel(helper.getLevel());
            Phase11StandardAe2GameTests.setActiveNode(normal, normal.getMainNode(), helper, fixture.grid, true);
            Phase11StandardAe2GameTests.setActiveNode(creative, creative.getMainNode(), helper, fixture.grid, true);
            var valid = PatternDetailsHelper.encodeProcessingPattern(
                    new GenericStack[]{new GenericStack(fixture.raw, 1)},
                    new GenericStack[]{new GenericStack(fixture.ingot, 1)});
            normal.getInternalInventory().setItemDirect(0, valid.copy());
            creative.getInternalInventory().setItemDirect(0, valid.copy());
            helper.assertTrue(normal.getPatternStatuses().get(0).diagnostic() == ValidationDiagnostic.LOCKED
                    && normal.getAvailablePatterns().isEmpty(), "Normal Provider ignored DENY_ALL");
            helper.assertTrue(creative.isCreative()
                    && creative.getPatternStatuses().get(0).diagnostic() == ValidationDiagnostic.AVAILABLE
                    && creative.getAvailablePatterns().size() == 1,
                    "Creative Provider failed to unlock a verified process");
            helper.assertTrue(ProviderAuthorDiagnostics.provider(true, creative.getPatternStatuses(),
                    ConfiguredRuleService.snapshot(helper.getLevel().getServer()), creative.installedGrants(), 1)
                    .orElseThrow().lines().stream().anyMatch(line -> line.contains("Creative override AVAILABLE")),
                    "Diagnostics omitted the normal versus Creative decision");
            fixture.register(creative);
            fixture.storage.items.set(fixture.raw, 1_000);
            fixture.submit(fixture.calculate(fixture.ingot, 1_000), fixture.requester);
            helper.assertTrue(fixture.execute() == 1 && fixture.requester.accepted == 1_000,
                    "Creative Provider did not use the shared one-dispatch Bulk path");

            var wrongOutput = PatternDetailsHelper.encodeProcessingPattern(
                    new GenericStack[]{new GenericStack(fixture.raw, 1)},
                    new GenericStack[]{new GenericStack(AEItemKey.of(Items.DIAMOND), 1)});
            creative.getInternalInventory().setItemDirect(0, wrongOutput);
            helper.assertTrue(creative.getAvailablePatterns().isEmpty()
                    && creative.getPatternStatuses().get(0).diagnostic() != ValidationDiagnostic.AVAILABLE,
                    "Creative override admitted a Pattern without a matching verified recipe");

            var taggedInput = new ItemStack(Items.RAW_IRON);
            taggedInput.getOrCreateTag().putInt("unsafe_marker", 1);
            creative.getInternalInventory().setItemDirect(0, PatternDetailsHelper.encodeProcessingPattern(
                    new GenericStack[]{new GenericStack(AEItemKey.of(taggedInput), 1)},
                    new GenericStack[]{new GenericStack(fixture.ingot, 1)}));
            helper.assertTrue(creative.getAvailablePatterns().isEmpty()
                    && creative.getPatternStatuses().get(0).diagnostic() != ValidationDiagnostic.AVAILABLE,
                    "Creative override admitted an NBT-bearing Item process");
        } finally {
            Phase23CInstallerGameTests.restoreRules(helper, original);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void providerVariantsHaveExpectedRecipesAndCreativeDropsOwnItem(GameTestHelper helper) {
        var relative = new BlockPos(1, 1, 1);
        var absolute = helper.absolutePos(relative);
        helper.setBlock(relative, AppliedCausalityContent.CREATIVE_SINGULAR_PATTERN_PROVIDER.get());
        var provider = (SingularPatternProviderBlockEntity) helper.getLevel().getBlockEntity(absolute);
        helper.assertTrue(provider != null && provider.isCreative(), "Creative BlockEntity type was not created");
        var recipeManager = helper.getLevel().getRecipeManager();
        var normalRecipe = recipeManager.byKey(ResourceLocation.fromNamespaceAndPath(
                AppliedCausality.MOD_ID, "singular_pattern_provider")).orElse(null);
        helper.assertTrue(normalRecipe instanceof ShapedRecipe
                        && normalRecipe.getResultItem(helper.getLevel().registryAccess()).is(
                        AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get()),
                "Normal Provider has no loaded Survival crafting recipe");
        helper.assertTrue(helper.getLevel().getServer().getAdvancements().getAdvancement(
                ResourceLocation.fromNamespaceAndPath(AppliedCausality.MOD_ID,
                        "recipes/misc/singular_pattern_provider")) != null,
                "Normal Provider recipe-book unlock was not loaded");
        helper.assertTrue(recipeManager.byKey(ResourceLocation.fromNamespaceAndPath(
                AppliedCausality.MOD_ID, "creative_singular_pattern_provider")).isEmpty(),
                "Creative block has a Survival recipe");
        helper.getLevel().destroyBlock(absolute, true);
        helper.runAfterDelay(2, () -> {
            var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(absolute).inflate(1.5)).stream().map(ItemEntity::getItem).toList();
            helper.assertTrue(drops.stream().filter(stack -> stack.is(
                    AppliedCausalityContent.CREATIVE_SINGULAR_PATTERN_PROVIDER_ITEM.get())).count() == 1,
                    "Creative block did not drop its own Item exactly once");
            helper.assertTrue(drops.stream().noneMatch(stack -> stack.is(
                    AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get())),
                    "Creative block dropped the normal Provider Item");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void creativeFallbackRecoveryKeepsVariantAndOutputOwnership(GameTestHelper helper) throws Exception {
        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        var provider = new SingularPatternProviderBlockEntity(helper.absolutePos(new BlockPos(1, 1, 1)),
                AppliedCausalityContent.CREATIVE_SINGULAR_PATTERN_PROVIDER.get().defaultBlockState());
        provider.setLevel(helper.getLevel());
        Phase11StandardAe2GameTests.setActiveNode(provider, provider.getMainNode(), helper, fixture.grid, true);
        provider.getInternalInventory().setItemDirect(0, PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(fixture.raw, 1)},
                new GenericStack[]{new GenericStack(fixture.ingot, 1)}));
        var input = new KeyCounter();
        input.set(fixture.raw, 1);
        helper.assertTrue(provider.pushPattern(provider.getAvailablePatterns().get(0), new KeyCounter[]{input}),
                "Creative fallback did not accept a verified process");
        var drops = new ArrayList<net.minecraft.world.item.ItemStack>();
        provider.addAdditionalDrops(helper.getLevel(), provider.getBlockPos(), drops);
        provider.clearContent();
        provider.addAdditionalDrops(helper.getLevel(), provider.getBlockPos(), drops);
        var carriers = drops.stream().filter(stack -> stack.is(
                AppliedCausalityContent.CREATIVE_SINGULAR_PATTERN_PROVIDER_ITEM.get())).toList();
        helper.assertTrue(carriers.size() == 1 && drops.stream().noneMatch(stack -> stack.is(
                AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get())),
                "Creative fallback recovery duplicated or changed the Provider variant");
        var outputs = carriers.get(0).getTag().getCompound("BlockEntityTag")
                .getCompound("applied_causality").getCompound("fallback").getList("outputs", Tag.TAG_COMPOUND);
        helper.assertTrue(outputs.size() == 1 && ((CompoundTag) outputs.get(0)).getLong("amount") == 1,
                "Creative fallback recovery lost or duplicated output ownership");
        helper.succeed();
    }
}
