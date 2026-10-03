package dev.appliedcausality.blockentity;

import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBinding;
import dev.appliedcausality.internal.binding.ProviderBindingStore;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import dev.appliedcausality.test.TestRecipeLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingularPatternProviderBlockEntityTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void patternInventoryAndDataVersionRoundTripThroughProviderNbt() {
        var source = createProvider();
        source.getInternalInventory().setItemDirect(0, new ItemStack(Items.STONE));
        CompoundTag saved = new CompoundTag();

        source.saveAdditional(saved);

        assertEquals(SingularPatternProviderBlockEntity.DATA_VERSION,
                saved.getCompound("applied_causality").getInt("dataVersion"));
        var restored = createProvider();
        restored.loadTag(saved);
        assertTrue(restored.getInternalInventory().getStackInSlot(0).is(Items.STONE));
        assertEquals(1, restored.getInternalInventory().getStackInSlot(0).getCount());
        assertTrue(restored.getAvailablePatterns().isEmpty(),
                "derived advertisement must not be restored from NBT");
    }

    @Test
    void blockEntityAdditionalDropsReturnStoredPatterns() {
        var provider = createProvider();
        provider.getInternalInventory().setItemDirect(0, new ItemStack(Items.STONE));
        var drops = new ArrayList<ItemStack>();

        provider.addAdditionalDrops(
                TestRecipeLevel.create(new RecipeManager(ICondition.IContext.EMPTY)),
                BlockPos.ZERO,
                drops);

        assertEquals(1, drops.size());
        assertTrue(drops.get(0).is(Items.STONE));
        assertEquals(1, drops.get(0).getCount());
    }

    @Test
    void retainedInstallersSurviveSaveLoadAndDropOnceWithRemovedDefinitions() {
        var source = createProvider();
        source.getInstallerInventory().setItemDirect(2, new ItemStack(Items.STONE));
        var saved = new CompoundTag();
        source.saveAdditional(saved);

        var restored = createProvider();
        restored.loadTag(saved);
        assertTrue(restored.getInstallerInventory().getStackInSlot(2).is(Items.STONE));
        assertEquals(1, restored.getInstallerInventory().getStackInSlot(2).getCount());
        var drops = new ArrayList<ItemStack>();
        restored.addAdditionalDrops(TestRecipeLevel.create(new RecipeManager(ICondition.IContext.EMPTY)),
                BlockPos.ZERO, drops);
        assertEquals(1, drops.size());
        assertTrue(drops.get(0).is(Items.STONE));
        restored.clearContent();
        assertTrue(restored.getInstallerInventory().getStackInSlot(2).isEmpty());
        assertEquals(1, drops.get(0).getCount());
    }

    @Test
    void malformedInstallerOwnershipIsRejected() {
        var provider = createProvider();
        var saved = new CompoundTag();
        provider.saveAdditional(saved);
        var entry = new ItemStack(Items.STONE).save(new CompoundTag());
        entry.putInt("Slot", 0);
        var duplicate = new net.minecraft.nbt.ListTag();
        duplicate.add(entry);
        duplicate.add(entry.copy());
        saved.getCompound("applied_causality").put("installers", duplicate);
        assertThrows(IllegalArgumentException.class, () -> createProvider().loadTag(saved));
    }

    @Test
    void providerBindingPersistsBesidePatternAndReplacementInvalidatesIt() {
        var source = createProvider();
        ItemStack originalPattern = new ItemStack(Items.STONE);
        originalPattern.getOrCreateTag().putString("encoded", "original");
        source.getInternalInventory().setItemDirect(0, originalPattern);
        var store = ProviderBindingStore.empty(SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
        var binding = new ProcessBinding(
                0,
                PatternFingerprint.fromDefinition(originalPattern),
                id("adapter"),
                id("recipe"));
        store.put(binding);
        CompoundTag saved = new CompoundTag();
        source.saveAdditional(saved);
        saved.getCompound("applied_causality").put("bindings", store.save());

        var restored = createProvider();
        restored.loadTag(saved);
        CompoundTag roundTripped = new CompoundTag();
        restored.saveAdditional(roundTripped);
        ProviderBindingStore restoredStore = ProviderBindingStore.load(
                roundTripped.getCompound("applied_causality").getCompound("bindings"),
                SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);

        assertEquals(binding, restoredStore.matching(0, binding.fingerprint()).orElseThrow());
        assertEquals("original", restored.getInternalInventory().getStackInSlot(0).getTag().getString("encoded"));

        restored.getInternalInventory().setItemDirect(0, new ItemStack(Items.DIRT));
        CompoundTag afterReplacement = new CompoundTag();
        restored.saveAdditional(afterReplacement);
        ProviderBindingStore afterReplacementStore = ProviderBindingStore.load(
                afterReplacement.getCompound("applied_causality").getCompound("bindings"),
                SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);

        assertEquals(0, afterReplacementStore.size());
    }

    @Test
    void versionOneProviderDataMigratesInventoryWithoutTrustingBindings() {
        var source = createProvider();
        ItemStack pattern = new ItemStack(Items.STONE);
        source.getInternalInventory().setItemDirect(0, pattern);
        CompoundTag saved = new CompoundTag();
        source.saveAdditional(saved);
        var staleStore = ProviderBindingStore.empty(SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
        staleStore.put(new ProcessBinding(
                0,
                PatternFingerprint.fromDefinition(pattern),
                id("adapter"),
                id("recipe")));
        CompoundTag oldProviderData = saved.getCompound("applied_causality");
        oldProviderData.putInt("dataVersion", 1);
        oldProviderData.put("bindings", staleStore.save());
        oldProviderData.remove("installers");

        var restored = createProvider();
        restored.loadTag(saved);
        CompoundTag migrated = new CompoundTag();
        restored.saveAdditional(migrated);

        assertTrue(restored.getInternalInventory().getStackInSlot(0).is(Items.STONE));
        assertEquals(SingularPatternProviderBlockEntity.DATA_VERSION,
                migrated.getCompound("applied_causality").getInt("dataVersion"));
        assertEquals(0, ProviderBindingStore.load(
                migrated.getCompound("applied_causality").getCompound("bindings"),
                SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT).size());
    }

    @Test
    void versionTwoBindingsMigrateWithoutInventingFallbackWork() {
        var provider = createProvider();
        var pattern = new ItemStack(Items.STONE);
        provider.getInternalInventory().setItemDirect(0, pattern);
        var store = ProviderBindingStore.empty(SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
        var binding = new ProcessBinding(0, PatternFingerprint.fromDefinition(pattern), id("adapter"), id("recipe"));
        store.put(binding);
        var saved = new CompoundTag();
        provider.saveAdditional(saved);
        var data = saved.getCompound("applied_causality");
        data.putInt("dataVersion", 2);
        data.put("bindings", store.save());
        data.remove("fallback");
        data.remove("installers");

        var restored = createProvider();
        restored.loadTag(saved);
        var migrated = new CompoundTag();
        restored.saveAdditional(migrated);
        var restoredStore = ProviderBindingStore.load(
                migrated.getCompound("applied_causality").getCompound("bindings"),
                SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
        assertEquals(binding, restoredStore.matching(0, binding.fingerprint()).orElseThrow());
        assertTrue(migrated.getCompound("applied_causality").getCompound("fallback")
                .getList("outputs", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty());
    }

    @Test
    void versionThreeBindingAndFallbackMigrateWithEmptyInstallers() {
        var source = createProvider();
        var definition = new ItemStack(Items.STONE);
        source.getInternalInventory().setItemDirect(0, definition);
        var store = ProviderBindingStore.empty(SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
        var binding = new ProcessBinding(0, PatternFingerprint.fromDefinition(definition),
                id("adapter"), id("recipe"));
        store.put(binding);
        var saved = new CompoundTag();
        source.saveAdditional(saved);
        var providerData = saved.getCompound("applied_causality");
        providerData.putInt("dataVersion", 3);
        providerData.put("bindings", store.save());
        providerData.remove("installers");

        var restored = createProvider();
        restored.loadTag(saved);
        var upgraded = new CompoundTag();
        restored.saveAdditional(upgraded);
        assertEquals(binding, ProviderBindingStore.load(
                upgraded.getCompound("applied_causality").getCompound("bindings"),
                SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT)
                .matching(0, binding.fingerprint()).orElseThrow());
        assertTrue(restored.getInstallerInventory().getStackInSlot(0).isEmpty());
    }

    @Test
    void unknownSchemaOrMissingCurrentFallbackFailsExplicitly() {
        var provider = createProvider();
        var saved = new CompoundTag();
        provider.saveAdditional(saved);
        var missing = saved.copy();
        missing.getCompound("applied_causality").remove("fallback");
        assertThrows(IllegalArgumentException.class, () -> createProvider().loadTag(missing));
        saved.getCompound("applied_causality").putInt("dataVersion", 99);
        assertThrows(IllegalArgumentException.class, () -> createProvider().loadTag(saved));
        saved.getCompound("applied_causality").putDouble("dataVersion", 3.5);
        assertThrows(IllegalArgumentException.class, () -> createProvider().loadTag(saved));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private static SingularPatternProviderBlockEntity createProvider() {
        BlockEntityType<SingularPatternProviderBlockEntity> type =
                BlockEntityType.Builder.<SingularPatternProviderBlockEntity>of(
                        (pos, state) -> {
                            throw new UnsupportedOperationException("test factory is not used");
                        },
                        Blocks.IRON_BLOCK).build(null);
        return new SingularPatternProviderBlockEntity(type, BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState());
    }
}
