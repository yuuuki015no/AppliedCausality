package dev.appliedcausality.internal.provider;

import appeng.api.crafting.IPatternDetails;
import appeng.api.inventories.InternalInventory;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.internal.validation.ValidationResult;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBinding;
import dev.appliedcausality.internal.binding.ProviderBindingStore;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable derived advertisement/model/status snapshot for one provider. */
public final class ProviderPatternCache {
    private static final ProviderPatternCache EMPTY = new ProviderPatternCache(-1, List.of(), List.of());

    private final long generation;
    private final List<VerifiedPattern> verifiedPatterns;
    private final List<IPatternDetails> advertisedPatterns;
    private final List<ProviderPatternStatus> statuses;

    private ProviderPatternCache(
            long generation,
            List<VerifiedPattern> verifiedPatterns,
            List<ProviderPatternStatus> statuses) {
        this.generation = generation;
        this.verifiedPatterns = List.copyOf(verifiedPatterns);
        this.advertisedPatterns = this.verifiedPatterns.stream()
                .map(VerifiedPattern::pattern)
                .toList();
        this.statuses = List.copyOf(statuses);
    }

    public static ProviderPatternCache empty() {
        return EMPTY;
    }

    public static ProviderPatternCache rebuild(
            InternalInventory inventory,
            PatternDecoder decoder,
            PatternResolver resolver) {
        Objects.requireNonNull(inventory, "inventory");
        return rebuild(0,
                inventory,
                decoder,
                resolver,
                ProviderBindingStore.empty(inventory.size()),
                (binding, pattern) -> {
                    throw new IllegalStateException("unreachable bound resolver");
                });
    }

    public static ProviderPatternCache rebuild(
            InternalInventory inventory,
            PatternDecoder decoder,
            PatternResolver resolver,
            ProviderBindingStore bindings,
            BoundPatternResolver boundResolver) {
        return rebuild(0, inventory, decoder, resolver, bindings, boundResolver);
    }

    public static ProviderPatternCache rebuild(
            long generation,
            InternalInventory inventory,
            PatternDecoder decoder,
            PatternResolver resolver,
            ProviderBindingStore bindings,
            BoundPatternResolver boundResolver) {
        return rebuild(generation, inventory, decoder, resolver, bindings, boundResolver,
                ignored -> true, false);
    }

    public static ProviderPatternCache rebuild(
            long generation,
            InternalInventory inventory,
            PatternDecoder decoder,
            PatternResolver resolver,
            ProviderBindingStore bindings,
            BoundPatternResolver boundResolver,
            Predicate<VerifiedProcessModel> unlockPolicy,
            boolean showAvailability) {
        if (generation < 0) {
            throw new IllegalArgumentException("Negative cache generation");
        }
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(boundResolver, "boundResolver");
        Objects.requireNonNull(unlockPolicy, "unlockPolicy");

        SlotEvaluation[] evaluations = new SlotEvaluation[inventory.size()];
        Map<IPatternDetails, List<SlotEvaluation>> groups = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack definition = inventory.getStackInSlot(slot);
            if (definition.isEmpty()) {
                continue;
            }

            IPatternDetails pattern = decoder.decode(definition);
            if (pattern == null) {
                evaluations[slot] = SlotEvaluation.rejected(
                        slot, ValidationDiagnostic.UNSUPPORTED_PATTERN);
                continue;
            }

            PatternFingerprint fingerprint = PatternFingerprint.fromDefinition(definition);
            Optional<ProcessBinding> binding = bindings.matching(slot, fingerprint);
            SlotEvaluation evaluation = new SlotEvaluation(slot, binding, null);
            evaluations[slot] = evaluation;
            groups.computeIfAbsent(pattern, ignored -> new ArrayList<>()).add(evaluation);
        }

        List<VerifiedPattern> verifiedPatterns = new ArrayList<>();
        for (var group : groups.entrySet()) {
            List<SlotEvaluation> slots = group.getValue();
            Optional<BindingIdentity> selectedBinding = slots.get(0).binding().map(BindingIdentity::from);
            boolean conflictingBinding = slots.stream()
                    .skip(1)
                    .map(slot -> slot.binding().map(BindingIdentity::from))
                    .anyMatch(selection -> !selection.equals(selectedBinding));

            ValidationResult result;
            if (conflictingBinding) {
                result = rejected(ValidationDiagnostic.CONFLICTING_BINDING);
            } else if (slots.get(0).binding().isPresent()) {
                result = boundResolver.resolve(slots.get(0).binding().orElseThrow(), group.getKey());
            } else {
                result = resolver.resolve(group.getKey());
            }
            Objects.requireNonNull(result, "validation result");
            for (SlotEvaluation slot : slots) {
                slot.setResult(result);
            }
            if (result.model().isPresent()) {
                VerifiedProcessModel model = result.model().orElseThrow();
                boolean available = unlockPolicy.test(model);
                for (SlotEvaluation slot : slots) {
                    slot.setAvailable(available);
                }
                if (available) {
                    verifiedPatterns.add(new VerifiedPattern(group.getKey(), model));
                }
            }
        }

        List<ProviderPatternStatus> statuses = new ArrayList<>();
        for (SlotEvaluation evaluation : evaluations) {
            if (evaluation == null) {
                continue;
            }
            ValidationResult result = Objects.requireNonNull(evaluation.result(), "slot validation result");
            statuses.add(new ProviderPatternStatus(
                    evaluation.slot(),
                    result.model().isPresent() && showAvailability
                            ? (evaluation.available() ? ValidationDiagnostic.AVAILABLE : ValidationDiagnostic.LOCKED)
                            : result.diagnostic(),
                    result.model().map(VerifiedProcessModel::recipeId).orElse(null),
                    result.model().orElse(null),
                    result.rejection().orElse(null)));
        }
        return new ProviderPatternCache(generation, verifiedPatterns, statuses);
    }

    public boolean isCurrent(long activeGeneration) {
        return generation == activeGeneration;
    }

    public List<IPatternDetails> advertisedPatterns() {
        return advertisedPatterns;
    }

    public List<IPatternDetails> advertisedPatterns(long activeGeneration) {
        return isCurrent(activeGeneration) ? advertisedPatterns : List.of();
    }

    public List<ProviderPatternStatus> statuses() {
        return statuses;
    }

    public List<ProviderPatternStatus> statuses(long activeGeneration) {
        return isCurrent(activeGeneration) ? statuses : List.of();
    }

    /**
     * Compares only the externally observable availability and diagnostics.
     * Verified models are deliberately excluded because they are derived and
     * must be rebuilt even when the provider advertisement remains unchanged.
     */
    public boolean hasSameStatusAndAvailability(ProviderPatternCache other) {
        Objects.requireNonNull(other, "other");
        return advertisedPatterns.equals(other.advertisedPatterns)
                && statuses.equals(other.statuses);
    }

    public Optional<VerifiedProcessModel> verifiedModel(IPatternDetails pattern) {
        Objects.requireNonNull(pattern, "pattern");
        for (VerifiedPattern verifiedPattern : verifiedPatterns) {
            if (verifiedPattern.pattern().equals(pattern)) {
                return Optional.of(verifiedPattern.model());
            }
        }
        return Optional.empty();
    }

    public Optional<VerifiedProcessModel> verifiedModel(IPatternDetails pattern, long activeGeneration) {
        return isCurrent(activeGeneration) ? verifiedModel(pattern) : Optional.empty();
    }

    @FunctionalInterface
    public interface PatternDecoder {
        @Nullable
        IPatternDetails decode(ItemStack definition);
    }

    @FunctionalInterface
    public interface PatternResolver {
        ValidationResult resolve(IPatternDetails pattern);
    }

    @FunctionalInterface
    public interface BoundPatternResolver {
        ValidationResult resolve(ProcessBinding binding, IPatternDetails pattern);
    }

    private static ValidationResult rejected(ValidationDiagnostic diagnostic) {
        return new ValidationResult(diagnostic, Optional.empty());
    }

    private record BindingIdentity(
            ResourceLocation adapterId,
            ResourceLocation recipeId) {
        private static BindingIdentity from(ProcessBinding binding) {
            return new BindingIdentity(binding.adapterId(), binding.recipeId());
        }
    }

    private static final class SlotEvaluation {
        private final int slot;
        private final Optional<ProcessBinding> binding;
        @Nullable
        private ValidationResult result;
        private boolean available;

        private SlotEvaluation(
                int slot,
                Optional<ProcessBinding> binding,
                @Nullable ValidationResult result) {
            this.slot = slot;
            this.binding = Objects.requireNonNull(binding, "binding");
            this.result = result;
        }

        private static SlotEvaluation rejected(int slot, ValidationDiagnostic diagnostic) {
            return new SlotEvaluation(slot, Optional.empty(), ProviderPatternCache.rejected(diagnostic));
        }

        private int slot() {
            return slot;
        }

        private Optional<ProcessBinding> binding() {
            return binding;
        }

        @Nullable
        private ValidationResult result() {
            return result;
        }

        private void setResult(ValidationResult result) {
            this.result = Objects.requireNonNull(result, "result");
        }

        private void setAvailable(boolean available) {
            this.available = available;
        }

        private boolean available() {
            return available;
        }
    }

    private record VerifiedPattern(IPatternDetails pattern, VerifiedProcessModel model) {
        private VerifiedPattern {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(model, "model");
        }
    }
}
