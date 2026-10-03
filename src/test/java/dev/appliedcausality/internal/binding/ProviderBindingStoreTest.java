package dev.appliedcausality.internal.binding;

import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderBindingStoreTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void processBindingAndStoreRoundTripAllIdentityFields() {
        ProcessBinding binding = binding(3, Items.STONE, "adapter", "recipe");
        ProviderBindingStore source = ProviderBindingStore.empty(9);
        source.put(binding);

        ProviderBindingStore restored = ProviderBindingStore.load(source.save(), 9);

        assertEquals(1, restored.size());
        assertEquals(binding, restored.matching(3, binding.fingerprint()).orElseThrow());
        assertTrue(restored.matching(2, binding.fingerprint()).isEmpty(), "slot remains part of identity");
    }

    @Test
    void replacementFingerprintOrEmptySlotRemovesStaleBinding() {
        ProcessBinding binding = binding(1, Items.STONE, "adapter", "recipe");
        ProviderBindingStore store = ProviderBindingStore.empty(9);
        store.put(binding);
        PatternFingerprint replacement = PatternFingerprint.fromDefinition(new ItemStack(Items.DIRT));

        assertTrue(store.matching(1, replacement).isEmpty());
        assertTrue(store.removeIfFingerprintMismatch(1, replacement));
        assertEquals(0, store.size());

        store.put(binding);
        assertTrue(store.removeIfFingerprintMismatch(1, null));
        assertFalse(store.removeIfFingerprintMismatch(1, null));
    }

    @Test
    void duplicateSlotsMalformedEntriesAndUnknownVersionsFailClosed() {
        ProcessBinding first = binding(1, Items.STONE, "adapter_a", "recipe_a");
        ProcessBinding duplicate = binding(1, Items.STONE, "adapter_b", "recipe_b");
        CompoundTag saved = ProviderBindingStore.empty(9).save();
        ListTag entries = new ListTag();
        entries.add(first.save());
        entries.add(new CompoundTag());
        entries.add(duplicate.save());
        entries.add(binding(12, Items.DIRT, "adapter", "outside").save());
        saved.put("entries", entries);

        assertEquals(0, ProviderBindingStore.load(saved, 9).size());

        saved.putInt("version", ProviderBindingStore.DATA_VERSION + 1);
        assertEquals(0, ProviderBindingStore.load(saved, 9).size());
    }

    private static ProcessBinding binding(int slot, net.minecraft.world.item.Item item, String adapter, String recipe) {
        return new ProcessBinding(
                slot,
                PatternFingerprint.fromDefinition(new ItemStack(item)),
                id(adapter),
                id(recipe));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
