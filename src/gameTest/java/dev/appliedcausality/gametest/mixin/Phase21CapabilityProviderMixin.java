package dev.appliedcausality.gametest.mixin;

import dev.appliedcausality.internal.bulk.BulkExecutionMetrics;
import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts real Forge capability queries only in the isolated Phase 21 run. */
@Mixin(value = CapabilityProvider.class, remap = false)
abstract class Phase21CapabilityProviderMixin {
    @Inject(method = "getCapability", at = @At("HEAD"))
    private <T> void appliedCausality$phase21CapabilityCall(
            Capability<T> capability,
            Direction side,
            CallbackInfoReturnable<?> callback) {
        BulkExecutionMetrics.externalCapabilityCall();
    }
}
