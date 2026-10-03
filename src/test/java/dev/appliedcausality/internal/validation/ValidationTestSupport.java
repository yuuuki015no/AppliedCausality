package dev.appliedcausality.internal.validation;

import appeng.api.crafting.IPatternDetails;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.function.Function;

public final class ValidationTestSupport {
    private ValidationTestSupport() {
    }

    public static ValidationResult validate(
            ProcessCandidate candidate,
            IPatternDetails pattern,
            Function<ResourceLocation, Optional<ResourceLocation>> recipeLookup) {
        return ProcessModelValidator.validate(candidate, pattern, recipeLookup::apply);
    }
}
