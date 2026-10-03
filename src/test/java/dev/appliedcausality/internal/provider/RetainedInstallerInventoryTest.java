package dev.appliedcausality.internal.provider;

import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetainedInstallerInventoryTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void invalidSnapshotDoesNotPartiallyReplaceLiveOwnership() {
        var retained = new RetainedInstallerInventory(null, (slot, stack) -> true);
        retained.inventory().setItemDirect(0, new ItemStack(Items.STONE));
        var entries = new ListTag();
        CompoundTag valid = new ItemStack(Items.DIRT).save(new CompoundTag());
        valid.putInt("Slot", 1);
        entries.add(valid);
        entries.add(valid.copy());

        assertThrows(IllegalArgumentException.class, () -> retained.load(entries));
        assertTrue(retained.inventory().getStackInSlot(0).is(Items.STONE));
        assertTrue(retained.inventory().getStackInSlot(1).isEmpty());
    }

    @Test
    void disabledSlotStillSavesAndDropsItsStack() {
        var retained = new RetainedInstallerInventory(null, (slot, stack) -> true);
        retained.inventory().setItemDirect(3, new ItemStack(Items.STONE));
        assertTrue(retained.activeStacks(1).stream().allMatch(ItemStack::isEmpty));
        assertEquals(1, retained.drops().size());
        var restored = new RetainedInstallerInventory(null, (slot, stack) -> true);
        restored.load(retained.save());
        assertTrue(restored.inventory().getStackInSlot(3).is(Items.STONE));
    }
}
