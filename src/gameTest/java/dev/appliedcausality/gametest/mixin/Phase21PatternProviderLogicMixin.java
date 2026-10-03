package dev.appliedcausality.gametest.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.KeyCounter;
import appeng.helpers.patternprovider.PatternProviderLogic;
import dev.appliedcausality.internal.bulk.BulkExecutionMetrics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts calls to stock AE2's normal provider path in the Phase 21 run. */
@Mixin(value = PatternProviderLogic.class, remap = false)
abstract class Phase21PatternProviderLogicMixin {
    @Inject(method = "pushPattern", at = @At("HEAD"))
    private void appliedCausality$phase21NormalPush(
            IPatternDetails pattern,
            KeyCounter[] inputs,
            CallbackInfoReturnable<Boolean> callback) {
        BulkExecutionMetrics.normalPushPattern();
    }
}
