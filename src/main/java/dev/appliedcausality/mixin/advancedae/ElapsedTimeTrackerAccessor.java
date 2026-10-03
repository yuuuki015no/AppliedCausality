package dev.appliedcausality.mixin.advancedae;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.stacks.AEKeyType;
import net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker;

@Mixin(value = ElapsedTimeTracker.class, remap = false)
public interface ElapsedTimeTrackerAccessor {
    @Invoker(value = "decrementItems", remap = false)
    void bulk$decrementItems(long amount, AEKeyType keyType);
}
