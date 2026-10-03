package dev.appliedcausality.internal.provider;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FallbackTransactionTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void oneOperationHasExactLongQuantitiesAndConstantDeliveryWork() {
        for (long amount : new long[] {1, Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1,
                Long.MAX_VALUE / 2, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
            var transaction = new FallbackTransaction();
            var holder = inputs(amount);
            assertTrue(transaction.accept(model(amount, Map.of(output(), amount), Map.of()), holder, 10));
            assertEquals(amount, holder[0].get(input()), "borrowed input must remain unchanged");
            var calls = new AtomicInteger();
            transaction.deliver(10, (key, offered) -> { fail("same-tick output"); return 0L; }, () -> {});
            transaction.deliver(11, (key, offered) -> {
                assertEquals(output(), key);
                assertEquals(amount, offered);
                calls.incrementAndGet();
                return offered;
            }, () -> {});
            transaction.deliver(12, (key, offered) -> { fail("duplicate delivery"); return 0L; }, () -> {});
            assertEquals(1, calls.get());
            assertFalse(transaction.isBusy());
        }
    }

    @Test
    void rejectsUnverifiedWrongShortExcessNegativeAliasedAndOverflowingInputWithoutMutation() {
        var verified = model(1, Map.of(output(), 1L), Map.of());
        var transaction = new FallbackTransaction();
        assertFalse(transaction.accept(null, inputs(1), 0));
        assertFalse(transaction.accept(verified, null, 0));
        for (long amount : new long[] {0, -1, 2, Long.MAX_VALUE}) {
            var holder = inputs(amount);
            assertFalse(transaction.accept(verified, holder, 0));
            assertEquals(amount, holder[0].get(input()));
        }
        var wrong = new KeyCounter();
        wrong.add(output(), 1);
        assertFalse(transaction.accept(verified, new KeyCounter[] {wrong}, 0));
        assertFalse(transaction.accept(verified, new KeyCounter[] {null}, 0));
        var one = inputs(1)[0];
        assertFalse(transaction.accept(model(2, Map.of(output(), 1L), Map.of()),
                new KeyCounter[] {one, one}, 0));
        assertFalse(transaction.accept(verified, new KeyCounter[] {inputs(Long.MAX_VALUE)[0], one}, 0));
        assertFalse(transaction.isBusy());
    }

    @Test
    void aggregatesDuplicateKeysButDoesNotAcceptASecondOperationWhilePending() {
        var transaction = new FallbackTransaction();
        var verified = model(2, Map.of(output(), 3L), Map.of(output(), 2L));
        assertTrue(transaction.accept(verified, new KeyCounter[] {inputs(1)[0], inputs(1)[0]}, 0));
        assertFalse(transaction.accept(verified, inputs(2), 0));
        assertEquals(5, amount(transaction.save()));
    }

    @Test
    void combinedOutputOverflowRejectsBeforeInputOwnershipChanges() {
        var transaction = new FallbackTransaction();
        assertFalse(transaction.accept(model(1, Map.of(output(), Long.MAX_VALUE), Map.of(output(), 1L)),
                inputs(1), 0));
        assertFalse(transaction.isBusy());
    }

    @Test
    void partialZeroAndReloadRetainExactlyTheRemainder() {
        var transaction = new FallbackTransaction();
        assertTrue(transaction.accept(model(1, Map.of(output(), 10L), Map.of()), inputs(1), 1));
        transaction.deliver(2, (key, offered) -> 2L, () -> {});
        assertEquals(8, amount(transaction.save()));
        transaction.deliver(2, (key, offered) -> { fail("same tick retry"); return 0L; }, () -> {});
        var restored = FallbackTransaction.load(transaction.save());
        restored.deliver(100, (key, offered) -> { fail("first load tick must defer"); return 0L; }, () -> {});
        restored.deliver(101, (key, offered) -> 0L, () -> {});
        assertEquals(8, amount(restored.save()));
        restored.deliver(102, (key, offered) -> { assertEquals(8, offered); return offered; }, () -> {});
        assertFalse(restored.isBusy());
    }

    @Test
    void nestedDeliveryAndRemovalWaitForExactSettlement() {
        for (long accepted : new long[] {0, 2, 10}) {
            var transaction = new FallbackTransaction();
            var verified = model(1, Map.of(output(), 10L), Map.of());
            assertTrue(transaction.accept(verified, inputs(1), 0));
            transaction.deliver(1, (key, offered) -> {
                transaction.deliver(2, (nestedKey, nestedAmount) -> { fail("reentry"); return 0L; }, () -> {});
                assertFalse(transaction.accept(verified, inputs(1), 1));
                assertTrue(transaction.save().getBoolean("uncertain"));
                transaction.beginRemoval();
                assertThrows(IllegalStateException.class, transaction::takeForRemoval);
                return accepted;
            }, () -> {});
            assertEquals(10 - accepted, amount(transaction.takeForRemoval()));
            assertEquals(0, amount(transaction.takeForRemoval()));
            assertFalse(transaction.hasOutput());
            assertTrue(transaction.isBusy(), "removed transaction cannot accept another operation");
        }
    }

    @Test
    void unknownAcceptanceIsPersistentlyQuarantinedInsteadOfGuessedAsZero() {
        for (int failure : new int[] {0, 1, 2}) {
            var transaction = new FallbackTransaction();
            assertTrue(transaction.accept(model(1, Map.of(output(), 10L), Map.of()), inputs(1), 0));
            assertThrows(IllegalStateException.class, () -> transaction.deliver(1, (key, offered) -> {
                if (failure == 0) throw new IllegalStateException("unknown external side effects");
                return failure == 1 ? -1L : 11L;
            }, () -> {}));
            assertTrue(transaction.save().getBoolean("uncertain"));
            var restored = FallbackTransaction.load(transaction.save());
            restored.deliver(2, (key, offered) -> { fail("uncertain delivery retried"); return 0L; }, () -> {});
            assertTrue(restored.isBusy());
            var recovered = FallbackTransaction.load(restored.takeForRemoval());
            assertTrue(recovered.save().getBoolean("uncertain"));
            recovered.deliver(3, (key, offered) -> { fail("quarantined carrier retried"); return 0L; }, () -> {});
        }
    }

    @Test
    void malformedPersistenceNeverSilentlyDiscardsOutput() {
        var transaction = new FallbackTransaction();
        transaction.accept(model(1, Map.of(output(), 1L), Map.of()), inputs(1), 0);
        var missing = transaction.save();
        missing.remove("outputs");
        assertThrows(IllegalArgumentException.class, () -> FallbackTransaction.load(missing));
        var future = transaction.save();
        future.putInt("dataVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> FallbackTransaction.load(future));
        var duplicate = transaction.save();
        var list = duplicate.getList("outputs", Tag.TAG_COMPOUND);
        list.add(list.getCompound(0).copy());
        assertThrows(IllegalArgumentException.class, () -> FallbackTransaction.load(duplicate));
        var zero = transaction.save();
        zero.getList("outputs", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", 0);
        assertThrows(IllegalArgumentException.class, () -> FallbackTransaction.load(zero));
    }

    private static long amount(CompoundTag saved) {
        return saved.getList("outputs", Tag.TAG_COMPOUND).stream()
                .mapToLong(tag -> ((CompoundTag) tag).getLong("amount")).sum();
    }

    @Test
    void quarantineFlagRequiresAnExplicitCanonicalBooleanByte() {
        var transaction = new FallbackTransaction();
        transaction.accept(model(1, Map.of(output(), 1L), Map.of()), inputs(1), 0);
        var missing = transaction.save();
        missing.remove("uncertain");
        assertThrows(IllegalArgumentException.class, () -> FallbackTransaction.load(missing));
        for (Tag invalid : new Tag[] {
                net.minecraft.nbt.StringTag.valueOf("true"), new CompoundTag(), new net.minecraft.nbt.ListTag(),
                net.minecraft.nbt.IntTag.valueOf(0), net.minecraft.nbt.LongTag.valueOf(1),
                net.minecraft.nbt.ByteTag.valueOf((byte) -1), net.minecraft.nbt.ByteTag.valueOf((byte) 2)}) {
            var saved = transaction.save();
            saved.put("uncertain", invalid);
            assertThrows(IllegalArgumentException.class, () -> FallbackTransaction.load(saved));
        }
        for (boolean uncertain : new boolean[] {false, true}) {
            var saved = transaction.save();
            saved.putBoolean("uncertain", uncertain);
            var restored = FallbackTransaction.load(saved);
            assertEquals(uncertain, restored.save().getBoolean("uncertain"));
        }
    }

    @Test
    void versionCannotBeCoercedFromFractionalOrWideNumericTag() {
        var transaction = new FallbackTransaction();
        transaction.accept(model(1, Map.of(output(), 1L), Map.of()), inputs(1), 0);
        for (Tag version : new Tag[] {net.minecraft.nbt.DoubleTag.valueOf(1.5),
                net.minecraft.nbt.LongTag.valueOf(1), net.minecraft.nbt.ByteTag.valueOf((byte) 1)}) {
            var data = transaction.save();
            data.put("dataVersion", version);
            assertThrows(IllegalArgumentException.class, () -> FallbackTransaction.load(data));
        }
        assertEquals(1, amount(transaction.save()));
    }

    private static KeyCounter[] inputs(long amount) {
        var counter = new KeyCounter();
        counter.set(input(), amount);
        return new KeyCounter[] {counter};
    }

    private static AEKey input() { return AEItemKey.of(Items.RAW_IRON); }
    private static AEKey output() { return AEItemKey.of(Items.IRON_INGOT); }

    private static VerifiedProcessModel model(long inputAmount, Map<AEKey, Long> outputs,
            Map<AEKey, Long> remaining) {
        try {
            Constructor<VerifiedProcessModel> constructor = VerifiedProcessModel.class.getDeclaredConstructor(
                    ResourceLocation.class, ResourceLocation.class, ResourceLocation.class, ProcessShapeId.class,
                    ResourceLocation.class, ResourceLocation.class, Map.class, Map.class, Map.class,
                    ProcessProperties.class);
            constructor.setAccessible(true);
            var id = ResourceLocation.fromNamespaceAndPath("test", "fallback");
            return constructor.newInstance(id, id, id, ProcessShapeId.of("test", "item_to_item"), id, null,
                    Map.of(input(), inputAmount), outputs, remaining, new ProcessProperties(true, true, true, true));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
