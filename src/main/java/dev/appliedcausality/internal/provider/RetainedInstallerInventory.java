package dev.appliedcausality.internal.provider;

import appeng.api.inventories.InternalInventory;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.filter.IAEItemFilter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/** Owns retained stacks independently of AE2's exposed Processing Pattern inventory. */
public final class RetainedInstallerInventory {
    public static final int SLOT_COUNT = 4;

    private final AppEngInternalInventory inventory;

    public RetainedInstallerInventory(InternalInventoryHost host, BiPredicate<Integer, ItemStack> canInsert) {
        Objects.requireNonNull(canInsert);
        inventory = new AppEngInternalInventory(host, SLOT_COUNT, 1, new IAEItemFilter() {
            @Override
            public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
                return canInsert.test(slot, stack);
            }
        });
    }

    public InternalInventory inventory() {
        return inventory;
    }

    public List<ItemStack> activeStacks(int activeSlots) {
        if (activeSlots < 1 || activeSlots > SLOT_COUNT) {
            throw new IllegalArgumentException("Active Installer slots out of range");
        }
        List<ItemStack> stacks = new ArrayList<>(activeSlots);
        for (int slot = 0; slot < activeSlots; slot++) {
            stacks.add(inventory.getStackInSlot(slot));
        }
        return List.copyOf(stacks);
    }

    public List<ItemStack> drops() {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        return stacks;
    }

    public void clear() {
        inventory.clear();
    }

    public ListTag save() {
        ListTag entries = new ListTag();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                CompoundTag entry = stack.save(new CompoundTag());
                entry.putInt("Slot", slot);
                entries.add(entry);
            }
        }
        return entries;
    }

    public void load(ListTag entries) {
        if (entries.size() > SLOT_COUNT) {
            throw new IllegalArgumentException("Too many Installer entries");
        }
        ItemStack[] parsed = new ItemStack[SLOT_COUNT];
        java.util.Arrays.fill(parsed, ItemStack.EMPTY);
        var seen = new HashSet<Integer>();
        for (int index = 0; index < entries.size(); index++) {
            if (!(entries.get(index) instanceof CompoundTag entry)) {
                throw new IllegalArgumentException("Invalid Installer entry type");
            }
            int slot = entry.getInt("Slot");
            ItemStack stack = ItemStack.of(entry);
            if (!entry.contains("Slot", Tag.TAG_INT) || slot < 0 || slot >= SLOT_COUNT
                    || !seen.add(slot) || stack.isEmpty() || stack.getCount() != 1) {
                throw new IllegalArgumentException("Invalid retained Installer entry");
            }
            parsed[slot] = stack;
        }
        // Parse the complete ownership snapshot before mutating any live slot.
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            inventory.setItemDirect(slot, parsed[slot]);
        }
    }
}
