package dev.appliedcausality.internal.provider;

import appeng.api.inventories.InternalInventory;
import appeng.core.definitions.AEItems;
import appeng.util.inv.filter.IAEItemFilter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.Supplier;

/** Accepts only AE2's normal encoded Processing Pattern item. */
public final class ProcessingPatternItemFilter implements IAEItemFilter {
    public static final ProcessingPatternItemFilter INSTANCE =
            new ProcessingPatternItemFilter(() -> AEItems.PROCESSING_PATTERN.asItem());

    private final Supplier<Item> processingPatternItem;

    private ProcessingPatternItemFilter(Supplier<Item> processingPatternItem) {
        this.processingPatternItem = Objects.requireNonNull(processingPatternItem, "processingPatternItem");
    }

    static ProcessingPatternItemFilter forItem(Item item) {
        Objects.requireNonNull(item, "item");
        return new ProcessingPatternItemFilter(() -> item);
    }

    @Override
    public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
        return stack.is(processingPatternItem.get());
    }
}
