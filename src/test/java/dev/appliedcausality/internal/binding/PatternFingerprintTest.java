package dev.appliedcausality.internal.binding;

import appeng.api.stacks.AEItemKey;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternFingerprintTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void ignoresPhysicalCountWithoutMutatingThePatternItem() {
        ItemStack one = new ItemStack(Items.STONE, 1);
        one.getOrCreateTag().putLong("amount", 4_000_000_000L);
        ItemStack many = one.copy();
        many.setCount(64);
        CompoundTag before = many.save(new CompoundTag());

        PatternFingerprint oneFingerprint = PatternFingerprint.fromDefinition(one);
        PatternFingerprint manyFingerprint = PatternFingerprint.fromDefinition(many);

        assertEquals(oneFingerprint, manyFingerprint);
        assertEquals(before, many.save(new CompoundTag()), "fingerprinting must be read-only");
    }

    @Test
    void canonicalizesCompoundKeysButPreservesExactDefinitionSemantics() {
        CompoundTag firstTag = new CompoundTag();
        firstTag.putLong("amount", 4_000_000_000L);
        firstTag.put("slots", slots("minecraft:iron_ore", "minecraft:gold_ore"));
        CompoundTag secondTag = new CompoundTag();
        secondTag.put("slots", slots("minecraft:iron_ore", "minecraft:gold_ore"));
        secondTag.putLong("amount", 4_000_000_000L);

        PatternFingerprint first = fingerprint("minecraft:stone", firstTag, caps(1));
        PatternFingerprint reorderedKeys = fingerprint("minecraft:stone", secondTag, caps(1));
        PatternFingerprint changedSlotOrder = fingerprint(
                "minecraft:stone", tag(4_000_000_000L, slots("minecraft:gold_ore", "minecraft:iron_ore")), caps(1));
        PatternFingerprint changedInternalEmptySlot = fingerprint(
                "minecraft:stone", tag(4_000_000_000L, slots("minecraft:iron_ore", "", "minecraft:gold_ore")), caps(1));
        PatternFingerprint changedLongAmount = fingerprint(
                "minecraft:stone", tag(4_000_000_001L, slots("minecraft:iron_ore", "minecraft:gold_ore")), caps(1));
        PatternFingerprint changedCaps = fingerprint("minecraft:stone", firstTag, caps(2));
        PatternFingerprint changedItem = fingerprint("minecraft:dirt", firstTag, caps(1));

        assertEquals(first, reorderedKeys);
        assertNotEquals(first, changedSlotOrder);
        assertNotEquals(first, changedInternalEmptySlot);
        assertNotEquals(first, changedLongAmount);
        assertNotEquals(first, changedCaps);
        assertNotEquals(first, changedItem);
    }

    @Test
    void persistedDigestRoundTripsAndRejectsUnknownOrMalformedVersions() {
        PatternFingerprint fingerprint = fingerprint("minecraft:stone", new CompoundTag(), new CompoundTag());
        CompoundTag saved = fingerprint.save();

        PatternFingerprint restored = PatternFingerprint.load(saved).orElseThrow();

        assertEquals(fingerprint, restored);
        assertArrayEquals(fingerprint.digest(), restored.digest());
        saved.putInt("version", PatternFingerprint.ENCODING_VERSION + 1);
        assertTrue(PatternFingerprint.load(saved).isEmpty());
        saved.putInt("version", PatternFingerprint.ENCODING_VERSION);
        saved.putByteArray("digest", new byte[3]);
        assertTrue(PatternFingerprint.load(saved).isEmpty());
    }

    @Test
    void preservesIsolatedUtf16SurrogatesWithoutReplacement() {
        CompoundTag isolatedSurrogateTag = new CompoundTag();
        isolatedSurrogateTag.putString("value", "\uD800");
        CompoundTag replacementCharacterTag = new CompoundTag();
        replacementCharacterTag.putString("value", "?");

        AEItemKey isolatedSurrogate = definition("minecraft:stone", isolatedSurrogateTag, new CompoundTag());
        AEItemKey replacementCharacter = definition("minecraft:stone", replacementCharacterTag, new CompoundTag());

        assertNotEquals(isolatedSurrogate, replacementCharacter);
        assertNotEquals(
                PatternFingerprint.fromDefinition(isolatedSurrogate),
                PatternFingerprint.fromDefinition(replacementCharacter));
    }

    private static PatternFingerprint fingerprint(String itemId, CompoundTag tag, CompoundTag caps) {
        return PatternFingerprint.fromDefinition(definition(itemId, tag, caps));
    }

    private static AEItemKey definition(String itemId, CompoundTag tag, CompoundTag caps) {
        CompoundTag definition = new CompoundTag();
        definition.putString("id", itemId);
        definition.put("tag", tag.copy());
        definition.put("caps", caps.copy());
        return AEItemKey.fromTag(definition);
    }

    private static CompoundTag tag(long amount, ListTag slots) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("amount", amount);
        tag.put("slots", slots);
        return tag;
    }

    private static CompoundTag caps(int value) {
        CompoundTag caps = new CompoundTag();
        caps.putInt("testCapability", value);
        return caps;
    }

    private static ListTag slots(String... values) {
        ListTag slots = new ListTag();
        for (String value : values) {
            CompoundTag slot = new CompoundTag();
            if (!value.isEmpty()) {
                slot.putString("key", value);
            }
            slots.add(slot);
        }
        return slots;
    }
}
