package dev.appliedcausality.internal.validation;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ResolvedProcess;
import dev.appliedcausality.internal.bulk.BulkLongMath;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Mandatory, fail-closed static validation between a recipe candidate and an
 * AE2 Processing Pattern.
 */
public final class ProcessModelValidator {
    private ProcessModelValidator() {
    }

    /**
     * Validates against the server-owned recipe manager in the supplied context.
     */
    public static ValidationResult validate(ProcessCandidate candidate, PatternContext context) {
        Objects.requireNonNull(context, "context");
        return validate(candidate, context.pattern(), recipeId -> context.recipeManager().byKey(recipeId)
                .map(recipe -> ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType())));
    }

    /**
     * Pure validation entry point used by tests and by future recipe discovery.
     */
    static ValidationResult validate(
            ProcessCandidate candidate,
            IPatternDetails pattern,
            RecipeLookup recipeLookup) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(recipeLookup, "recipeLookup");

        if (!(pattern instanceof AEProcessingPattern)) {
            return ValidationResult.rejected(ValidationDiagnostic.UNSUPPORTED_PATTERN);
        }

        ResolvedProcess process = candidate.process();
        Optional<ResourceLocation> actualTypeId = recipeLookup.findRecipeTypeId(process.recipeId());
        if (actualTypeId.isEmpty()) {
            return ValidationResult.rejected(ValidationDiagnostic.NO_RECIPE);
        }
        if (!actualTypeId.get().equals(process.recipeTypeId())) {
            return ValidationResult.rejected(ValidationDiagnostic.RECIPE_TYPE_MISMATCH);
        }

        ProcessProperties properties = process.properties();
        if (!properties.deterministicOutputs()) {
            return ValidationResult.rejected(ValidationDiagnostic.NON_DETERMINISTIC);
        }
        if (!properties.exactInputSemantics()) {
            return ValidationResult.rejected(ValidationDiagnostic.SUBSTITUTION_UNSUPPORTED);
        }
        if (!properties.remainingItemsFullyModeled()) {
            return ValidationResult.rejected(ValidationDiagnostic.REMAINING_ITEMS_UNSUPPORTED);
        }
        if (!properties.machineStateIndependent()) {
            return ValidationResult.rejected(ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED);
        }
        if (!hasSafePositiveAmounts(process.inputsPerOperation(), false)
                || !hasSafePositiveAmounts(process.outputsPerOperation(), false)
                || !hasSafePositiveAmounts(process.remainingItemsPerOperation(), true)) {
            return ValidationResult.rejected(ValidationDiagnostic.NUMERICALLY_UNSAFE);
        }

        PatternAmounts patternInputs = collectInputs(pattern);
        if (patternInputs.diagnostic() != ValidationDiagnostic.VALID) {
            return ValidationResult.rejected(patternInputs.diagnostic());
        }
        if (!patternInputs.amounts().equals(process.inputsPerOperation())) {
            return ValidationResult.rejected(ValidationDiagnostic.INPUT_MISMATCH);
        }
        if (!patternInputs.remainingItems().equals(process.remainingItemsPerOperation())) {
            return ValidationResult.rejected(ValidationDiagnostic.REMAINING_ITEMS_UNSUPPORTED);
        }

        PatternAmounts patternOutputs = collectOutputs(pattern);
        if (patternOutputs.diagnostic() != ValidationDiagnostic.VALID) {
            return ValidationResult.rejected(patternOutputs.diagnostic());
        }
        if (!patternOutputs.amounts().equals(process.outputsPerOperation())) {
            return ValidationResult.rejected(ValidationDiagnostic.OUTPUT_MISMATCH);
        }

        return ValidationResult.verified(VerifiedProcessModel.validated(
                candidate.adapterId(), process.recipeId(), process.recipeTypeId(), process.processShape(),
                process.processFamily(), process.machineProfile(), process.inputsPerOperation(),
                process.outputsPerOperation(), process.remainingItemsPerOperation(), properties));
    }

    private static PatternAmounts collectInputs(IPatternDetails pattern) {
        Map<AEKey, Long> inputs = new HashMap<>();
        Map<AEKey, Long> remainingItems = new HashMap<>();
        IPatternDetails.IInput[] patternInputs = pattern.getInputs();
        if (patternInputs == null) {
            return PatternAmounts.rejected(ValidationDiagnostic.INPUT_MISMATCH);
        }
        for (IPatternDetails.IInput input : patternInputs) {
            if (input == null) {
                return PatternAmounts.rejected(ValidationDiagnostic.INPUT_MISMATCH);
            }
            GenericStack[] possibleInputs = input.getPossibleInputs();
            if (possibleInputs == null || possibleInputs.length != 1 || possibleInputs[0] == null) {
                return PatternAmounts.rejected(ValidationDiagnostic.SUBSTITUTION_UNSUPPORTED);
            }
            GenericStack stack = possibleInputs[0];
            if (stack.amount() <= 0 || input.getMultiplier() <= 0) {
                return PatternAmounts.rejected(ValidationDiagnostic.NUMERICALLY_UNSAFE);
            }
            long amount;
            try {
                amount = BulkLongMath.checkedMultiply(stack.amount(), input.getMultiplier());
                addExact(inputs, stack.what(), amount);
                AEKey remaining = input.getRemainingKey(stack.what());
                if (remaining != null) {
                    addExact(remainingItems, remaining, input.getMultiplier());
                }
            } catch (ArithmeticException | IllegalArgumentException exception) {
                return PatternAmounts.rejected(ValidationDiagnostic.NUMERICALLY_UNSAFE);
            }
        }
        return PatternAmounts.valid(inputs, remainingItems);
    }

    private static PatternAmounts collectOutputs(IPatternDetails pattern) {
        Map<AEKey, Long> outputs = new HashMap<>();
        GenericStack[] patternOutputs = pattern.getOutputs();
        if (patternOutputs == null || patternOutputs.length == 0) {
            return PatternAmounts.rejected(ValidationDiagnostic.OUTPUT_MISMATCH);
        }
        try {
            for (GenericStack output : patternOutputs) {
                if (output == null || output.amount() <= 0) {
                    return PatternAmounts.rejected(ValidationDiagnostic.OUTPUT_MISMATCH);
                }
                addExact(outputs, output.what(), output.amount());
            }
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return PatternAmounts.rejected(ValidationDiagnostic.NUMERICALLY_UNSAFE);
        }
        return PatternAmounts.valid(outputs, Map.of());
    }

    private static boolean hasSafePositiveAmounts(Map<AEKey, Long> amounts, boolean mayBeEmpty) {
        if (!mayBeEmpty && amounts.isEmpty()) {
            return false;
        }
        for (var entry : amounts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                return false;
            }
        }
        return true;
    }

    private static void addExact(Map<AEKey, Long> amounts, AEKey key, long amount) {
        amounts.merge(Objects.requireNonNull(key, "key"), amount, BulkLongMath::checkedAdd);
    }

    @FunctionalInterface
    interface RecipeLookup {
        Optional<ResourceLocation> findRecipeTypeId(ResourceLocation recipeId);
    }

    private record PatternAmounts(
            ValidationDiagnostic diagnostic,
            Map<AEKey, Long> amounts,
            Map<AEKey, Long> remainingItems) {
        private static PatternAmounts valid(Map<AEKey, Long> amounts, Map<AEKey, Long> remainingItems) {
            return new PatternAmounts(ValidationDiagnostic.VALID, Map.copyOf(amounts), Map.copyOf(remainingItems));
        }

        private static PatternAmounts rejected(ValidationDiagnostic diagnostic) {
            return new PatternAmounts(diagnostic, Map.of(), Map.of());
        }
    }
}
