package dev.appliedcausality.mixin.advancedae;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob$TaskProgress", remap = false)
public interface TaskProgressAccessor {
    @Accessor(value = "value", remap = false)
    long bulk$getValue();

    @Accessor(value = "value", remap = false)
    void bulk$setValue(long value);
}
