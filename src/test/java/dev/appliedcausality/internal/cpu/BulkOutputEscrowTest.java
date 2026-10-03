package dev.appliedcausality.internal.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

class BulkOutputEscrowTest {
    private static final long ABOVE_INTEGER_MAX = (long) Integer.MAX_VALUE + 1L;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void aggregatesByDistinctKeyWithExactLongArithmetic() {
        var escrow = new BulkOutputEscrow();
        var iron = key(Items.IRON_INGOT);

        escrow.add(iron, ABOVE_INTEGER_MAX);
        escrow.add(iron, Long.MAX_VALUE - ABOVE_INTEGER_MAX);

        assertEquals(Long.MAX_VALUE, escrow.get(iron));
        assertEquals(1, escrow.distinctKeyCount());
        assertEquals(1, escrow.entries().size());
        assertThrows(ArithmeticException.class, () -> escrow.add(iron, 1));

        escrow.remove(iron, Long.MAX_VALUE - 1L);
        assertEquals(1L, escrow.get(iron));
        escrow.remove(iron, 1L);
        assertTrue(escrow.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> escrow.remove(iron, 1L));
        assertThrows(IllegalArgumentException.class, () -> escrow.add(iron, -1L));
    }

    @Test
    void saveLoadRestoresOneEntryPerDistinctKey() {
        var iron = key(Items.IRON_INGOT);
        var gold = key(Items.GOLD_INGOT);
        var escrow = new BulkOutputEscrow();
        escrow.add(iron, ABOVE_INTEGER_MAX);
        escrow.add(gold, Long.MAX_VALUE);

        CompoundTag saved = escrow.writeToNBT();
        assertEquals(BulkOutputEscrow.DATA_VERSION, saved.getInt("dataVersion"));
        assertEquals(2, saved.getList("bulkOutputEscrow", Tag.TAG_COMPOUND).size());

        var restored = new BulkOutputEscrow();
        restored.readFromNBT(saved);
        assertEquals(ABOVE_INTEGER_MAX, restored.get(iron));
        assertEquals(Long.MAX_VALUE, restored.get(gold));
        assertEquals(2, restored.distinctKeyCount());
    }

    @Test
    void fluidEscrowSurvivesRestartPartialDeliveryAndCancelTransfer() {
        var fluid = AEFluidKey.of(Fluids.WATER);
        var original = new BulkOutputEscrow();
        original.add(fluid, ABOVE_INTEGER_MAX);
        var savedJob = new CompoundTag();
        BulkJobEscrowPersistence.write(savedJob, original);

        var restored = BulkJobEscrowPersistence.read(savedJob);
        assertEquals(ABOVE_INTEGER_MAX, restored.get(fluid));
        assertEquals(9L, BulkEscrowOperations.deliver(restored, fluid, ABOVE_INTEGER_MAX,
                (key, amount) -> 9L));
        assertEquals(ABOVE_INTEGER_MAX - 9L, restored.get(fluid));
        assertEquals(0L, BulkEscrowOperations.deliver(restored, fluid, restored.get(fluid),
                (key, amount) -> 0L));

        Map<AEKey, Long> cpuInventory = new HashMap<>();
        BulkEscrowOperations.transferAll(restored,
                (key, amount) -> cpuInventory.merge(key, amount, Math::addExact));
        assertTrue(restored.isEmpty());
        assertEquals(ABOVE_INTEGER_MAX - 9L, cpuInventory.get(fluid));
    }

    @Test
    void perJobSidecarIsIndependentAndAbsentForEmptyEscrow() {
        var iron = key(Items.IRON_INGOT);
        var firstJob = new CompoundTag();
        var secondJob = new CompoundTag();
        var escrow = new BulkOutputEscrow();

        BulkJobEscrowPersistence.write(firstJob, escrow);
        assertFalse(firstJob.contains(BulkJobEscrowPersistence.JOB_DATA_KEY));

        escrow.add(iron, ABOVE_INTEGER_MAX);
        BulkJobEscrowPersistence.write(firstJob, escrow);
        assertTrue(firstJob.contains(BulkJobEscrowPersistence.JOB_DATA_KEY, Tag.TAG_COMPOUND));
        assertFalse(secondJob.contains(BulkJobEscrowPersistence.JOB_DATA_KEY));

        var restoredFirst = BulkJobEscrowPersistence.read(firstJob);
        var restoredSecond = BulkJobEscrowPersistence.read(secondJob);
        assertEquals(ABOVE_INTEGER_MAX, restoredFirst.get(iron));
        assertTrue(restoredSecond.isEmpty());
    }

    @Test
    void malformedOrUnsupportedPersistenceFailsClosed() {
        var missingList = new CompoundTag();
        missingList.putInt("dataVersion", BulkOutputEscrow.DATA_VERSION);
        assertThrows(IllegalArgumentException.class,
                () -> new BulkOutputEscrow().readFromNBT(missingList));

        var futureVersion = new CompoundTag();
        futureVersion.putInt("dataVersion", BulkOutputEscrow.DATA_VERSION + 1);
        futureVersion.put("bulkOutputEscrow", new ListTag());
        assertThrows(IllegalArgumentException.class,
                () -> new BulkOutputEscrow().readFromNBT(futureVersion));

        var invalidAmount = new CompoundTag();
        invalidAmount.putInt("dataVersion", BulkOutputEscrow.DATA_VERSION);
        var outputs = new ListTag();
        var entry = key(Items.IRON_INGOT).toTagGeneric();
        entry.putLong("#", 0L);
        outputs.add(entry);
        invalidAmount.put("bulkOutputEscrow", outputs);
        assertThrows(IllegalArgumentException.class,
                () -> new BulkOutputEscrow().readFromNBT(invalidAmount));

        var wrongListType = new CompoundTag();
        wrongListType.putInt("dataVersion", BulkOutputEscrow.DATA_VERSION);
        var strings = new ListTag();
        strings.add(StringTag.valueOf("not an output"));
        wrongListType.put("bulkOutputEscrow", strings);
        assertThrows(IllegalArgumentException.class,
                () -> new BulkOutputEscrow().readFromNBT(wrongListType));

        var wrongSidecarType = new CompoundTag();
        wrongSidecarType.putString(BulkJobEscrowPersistence.JOB_DATA_KEY, "not a compound");
        assertThrows(IllegalArgumentException.class,
                () -> BulkJobEscrowPersistence.read(wrongSidecarType));
    }

    @Test
    void absentChemicalKeyAddonDoesNotSilentlyDiscardSavedEscrow() {
        var iron = key(Items.IRON_INGOT);
        var escrow = new BulkOutputEscrow();
        escrow.add(iron, ABOVE_INTEGER_MAX);

        var saved = escrow.writeToNBT();
        var unknownChemical = new CompoundTag();
        unknownChemical.putString("#c", "appmek:chemical");
        unknownChemical.putLong("#", 1L);
        saved.getList("bulkOutputEscrow", Tag.TAG_COMPOUND).add(unknownChemical);

        assertThrows(IllegalArgumentException.class, () -> escrow.readFromNBT(saved));
        assertEquals(ABOVE_INTEGER_MAX, escrow.get(iron));
        assertEquals(1, escrow.distinctKeyCount());
    }

    @Test
    void requesterPartialAndZeroAcceptancePreserveExactRemainder() {
        var iron = key(Items.IRON_INGOT);
        var escrow = new BulkOutputEscrow();
        escrow.add(iron, 10L);

        assertEquals(4L, BulkEscrowOperations.deliver(escrow, iron, 10L, (key, offered) -> 4L));
        assertEquals(6L, escrow.get(iron));

        assertEquals(0L, BulkEscrowOperations.deliver(escrow, iron, 6L, (key, offered) -> 0L));
        assertEquals(6L, escrow.get(iron));

        assertThrows(IllegalStateException.class,
                () -> BulkEscrowOperations.deliver(escrow, iron, 6L, (key, offered) -> 7L));
        assertEquals(6L, escrow.get(iron));
    }

    @Test
    void numericCoercionCannotChangePersistedOwnershipAndFailedLoadIsAtomic() {
        var escrow = new BulkOutputEscrow();
        var iron = key(Items.IRON_INGOT);
        escrow.add(iron, ABOVE_INTEGER_MAX);
        for (var malformed : java.util.List.of(
                net.minecraft.nbt.DoubleTag.valueOf(1.5), net.minecraft.nbt.IntTag.valueOf(1),
                net.minecraft.nbt.StringTag.valueOf("1"))) {
            var data = escrow.writeToNBT();
            data.getList("bulkOutputEscrow", Tag.TAG_COMPOUND).getCompound(0).put("#", malformed);
            assertThrows(IllegalArgumentException.class, () -> escrow.readFromNBT(data));
            assertEquals(ABOVE_INTEGER_MAX, escrow.get(iron));
        }
        var wrongVersionType = escrow.writeToNBT();
        wrongVersionType.putDouble("dataVersion", 1.5);
        assertThrows(IllegalArgumentException.class, () -> escrow.readFromNBT(wrongVersionType));
        assertEquals(ABOVE_INTEGER_MAX, escrow.get(iron));
    }

    @Test
    void completionRequiresBothFinalDeliveryAndEmptyEscrow() {
        var escrow = new BulkOutputEscrow();
        var iron = key(Items.IRON_INGOT);

        assertFalse(BulkEscrowOperations.canFinish(1L, escrow));
        assertTrue(BulkEscrowOperations.canFinish(0L, escrow));

        escrow.add(iron, 1L);
        assertFalse(BulkEscrowOperations.canFinish(0L, escrow));
        assertThrows(IllegalArgumentException.class,
                () -> BulkEscrowOperations.canFinish(-1L, escrow));
    }

    @Test
    void cancelTransferMovesEachDistinctKeyExactlyOnceBeforeDeletion() {
        var iron = key(Items.IRON_INGOT);
        var gold = key(Items.GOLD_INGOT);
        var escrow = new BulkOutputEscrow();
        escrow.add(iron, ABOVE_INTEGER_MAX);
        escrow.add(gold, 3L);
        Map<AEKey, Long> transferred = new HashMap<>();

        BulkEscrowOperations.transferAll(escrow, (key, amount) -> {
            assertEquals(0L, escrow.get(key), "ownership must leave escrow before destination insertion");
            transferred.merge(key, amount, Math::addExact);
        });

        assertTrue(escrow.isEmpty());
        assertEquals(ABOVE_INTEGER_MAX, transferred.get(iron));
        assertEquals(3L, transferred.get(gold));
        assertEquals(2, transferred.size());
    }

    private static AEItemKey key(net.minecraft.world.item.Item item) {
        return AEItemKey.of(item);
    }
}
