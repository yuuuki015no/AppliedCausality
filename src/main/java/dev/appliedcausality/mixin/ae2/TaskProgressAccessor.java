package dev.appliedcausality.mixin.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "appeng.crafting.execution.ExecutingCraftingJob$TaskProgress", remap = false)
public interface TaskProgressAccessor {
    @Accessor(value = "value", remap = false)
    long bulk$getValue();

    @Accessor(value = "value", remap = false)
    void bulk$setValue(long value);
}
