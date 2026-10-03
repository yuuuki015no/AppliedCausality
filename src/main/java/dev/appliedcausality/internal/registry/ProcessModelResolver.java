package dev.appliedcausality.internal.registry;

import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ResolvedProcess;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBinding;
import dev.appliedcausality.internal.binding.ProcessBindingTarget;
import dev.appliedcausality.internal.bulk.BulkExecutionMetrics;
import dev.appliedcausality.internal.config.ConfiguredRuleService;
import dev.appliedcausality.internal.config.ConfiguredRuleSnapshot;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ProcessRejection;
import dev.appliedcausality.internal.validation.ProcessModelValidator;
import dev.appliedcausality.internal.validation.RecipeCandidateSelector;
import dev.appliedcausality.internal.validation.ValidationResult;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves a processing pattern through the frozen RecipeType index and the
 * mandatory Core validation boundary.
 */
public final class ProcessModelResolver {
    private static final ResourceLocation MEKANISM_ID = ResourceLocation.fromNamespaceAndPath(
            "applied_causality", "mekanism");
    private ProcessModelResolver() {
    }

    public static ValidationResult resolve(PatternContext context) {
        BulkExecutionMetrics.recipeResolution();
        return resolve(ProcessAdapterRegistrationLifecycle.registry(), context);
    }

    /** Lists Core-validated manual-binding identities; the identity itself grants no execution authority. */
    public static List<ProcessBindingTarget> findBindingTargets(PatternContext context) {
        Objects.requireNonNull(context, "context");
        return findBindingTargets(ProcessAdapterRegistrationLifecycle.registry(), context);
    }

    static List<ProcessBindingTarget> findBindingTargets(BulkRecipeAdapterRegistry registry, PatternContext context) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        List<ProcessCandidate> candidates = new ArrayList<>(discoverCandidates(
                registry,
                recipeType -> recipesFor(context.recipeManager(), recipeType),
                (registeredAdapter, recipe) -> registeredAdapter.adapter().resolve(recipe, context)));
        candidates.addAll(discoverConfiguredCandidates(snapshot(context),
                recipeType -> recipesFor(context.recipeManager(), recipeType),
                (recipe, profile) -> ConfiguredStandardStaticItemResolver.resolve(
                        recipe, context.registryAccess(), context.pattern(), profile)));
        return candidates.stream()
                .sorted(Comparator.comparingInt(ProcessCandidate::adapterPriority).reversed()
                        .thenComparing(candidate -> candidate.adapterId().toString())
                        .thenComparing(candidate -> candidate.process().recipeId().toString()))
                .filter(candidate -> ProcessModelValidator.validate(candidate, context).model().isPresent())
                .map(candidate -> new ProcessBindingTarget(candidate.adapterId(), candidate.process().recipeId()))
                .distinct()
                .limit(ProcessBindingTarget.MAX_MENU_CHOICES)
                .toList();
    }

    static ValidationResult resolve(BulkRecipeAdapterRegistry registry, PatternContext context) {
        return resolve(registry, context, snapshot(context));
    }

    static ValidationResult resolve(BulkRecipeAdapterRegistry registry, PatternContext context,
            ConfiguredRuleSnapshot rules) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(rules, "rules");
        List<ProcessCandidate> candidates = new ArrayList<>(discoverCandidates(
                registry,
                recipeType -> recipesFor(context.recipeManager(), recipeType),
                (registeredAdapter, recipe) -> registeredAdapter.adapter().resolve(recipe, context)));
        List<ConfiguredFailure> configuredFailures = new ArrayList<>();
        candidates.addAll(discoverConfiguredCandidates(rules,
                recipeType -> recipesFor(context.recipeManager(), recipeType),
                (recipe, profile) -> {
                    var attempt = ConfiguredStandardStaticItemResolver.inspect(
                            recipe, context.registryAccess(), context.pattern(), profile);
                    // Unbound discovery must not blame a Recipe before its supported
                    // Ingredients match, or when its known output Item differs.
                    if (attempt.process().isEmpty() && attempt.attributableToPattern()) {
                        configuredFailures.add(new ConfiguredFailure(attempt.diagnostic(),
                                new ProcessRejection(ConfiguredStandardStaticItemResolver.ID,
                                        recipe.getId(), attempt.reason())));
                    }
                    return attempt.process();
                }));
        ValidationResult selected = selectValidatedCandidates(
                candidates,
                candidate -> ProcessModelValidator.validate(candidate, context));
        if (selected.model().isPresent() || selected.diagnostic() != ValidationDiagnostic.NO_RECIPE
                || configuredFailures.isEmpty()) {
            return selected;
        }
        ConfiguredFailure first = configuredFailures.stream()
                .min(Comparator.comparingInt(ConfiguredFailure::specificity)
                        .thenComparing(failure -> failure.evidence().recipeId().toString()))
                .orElseThrow();
        return new ValidationResult(first.diagnostic(), Optional.empty())
                .withRejection(first.evidence());
    }

    static ValidationResult selectValidatedCandidates(
            Collection<ProcessCandidate> candidates,
            Function<ProcessCandidate, ValidationResult> validator) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(validator, "validator");

        List<CandidateValidation> validations = RecipeCandidateSelector.stableOrder(candidates).stream()
                .map(candidate -> new CandidateValidation(
                        candidate,
                        Objects.requireNonNull(validator.apply(candidate), "candidate validation")))
                .toList();
        if (validations.isEmpty()) {
            return rejectedNoRecipe();
        }

        List<ProcessCandidate> validCandidates = validations.stream()
                .filter(validation -> validation.result().model().isPresent())
                .map(CandidateValidation::candidate)
                .toList();
        if (validCandidates.isEmpty()) {
            CandidateValidation first = validations.get(0);
            return withCandidateEvidence(first.result(), first.candidate());
        }

        var selection = RecipeCandidateSelector.select(validCandidates);
        if (selection.candidate().isEmpty()) {
            return new ValidationResult(selection.diagnostic(), Optional.empty());
        }
        ProcessCandidate selected = selection.candidate().orElseThrow();
        return validations.stream()
                .filter(validation -> validation.candidate().equals(selected))
                .map(CandidateValidation::result)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Re-resolves one persisted binding against the current server recipe and
     * passes the resulting candidate through mandatory Core validation.
     */
    public static ValidationResult resolveBound(PatternContext context, ProcessBinding binding) {
        BulkExecutionMetrics.recipeResolution();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(binding, "binding");
        return resolveBound(ProcessAdapterRegistrationLifecycle.registry(), context, binding);
    }

    static ValidationResult resolveBound(
            BulkRecipeAdapterRegistry registry,
            PatternContext context,
            ProcessBinding binding) {
        return resolveBound(registry, context, binding, snapshot(context));
    }

    static ValidationResult resolveBound(BulkRecipeAdapterRegistry registry, PatternContext context,
            ProcessBinding binding, ConfiguredRuleSnapshot rules) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(rules, "rules");

        if (context.pattern().getDefinition() == null
                || !binding.fingerprint().equals(
                PatternFingerprint.fromDefinition(context.pattern().getDefinition()))) {
            return new ValidationResult(ValidationDiagnostic.PATTERN_FINGERPRINT_MISMATCH, Optional.empty());
        }

        Optional<? extends Recipe<?>> recipe = context.recipeManager().byKey(binding.recipeId());
        if (recipe.isEmpty()) {
            return rejectedNoRecipe().withRejection(new ProcessRejection(
                    binding.adapterId(), binding.recipeId(), "RECIPE_NOT_FOUND"));
        }
        Recipe<?> boundRecipe = recipe.orElseThrow();
        if (binding.adapterId().equals(ConfiguredStandardStaticItemResolver.ID)) {
            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(boundRecipe.getType());
            ConfiguredRuleSnapshot.RecipeTypeProfile profile = rules.recipeTypes().get(typeId);
            if (profile == null) {
                return rejectedNoRecipe().withRejection(new ProcessRejection(
                        binding.adapterId(), binding.recipeId(), "UNCONFIGURED_RECIPE_TYPE"));
            }
            var attempt = ConfiguredStandardStaticItemResolver.inspect(
                    boundRecipe, context.registryAccess(), context.pattern(), profile);
            if (attempt.process().isEmpty()) {
                return new ValidationResult(attempt.diagnostic(), Optional.empty())
                        .withRejection(new ProcessRejection(binding.adapterId(), binding.recipeId(),
                                attempt.reason()));
            }
            var candidate = new ProcessCandidate(ConfiguredStandardStaticItemResolver.ID, 0,
                    attempt.process().orElseThrow());
            return withCandidateEvidence(ProcessModelValidator.validate(candidate, context), candidate);
        }
        ResourceLocation canonicalId = canonicalMekanismId(binding.adapterId(), boundRecipe);
        Optional<RegisteredProcessAdapter> registered = registry.adapterById(canonicalId);
        if (registered.isEmpty()) {
            return rejectedNoRecipe();
        }
        RegisteredProcessAdapter adapter = registered.orElseThrow();
        boolean supportsExactType = registry.adaptersFor(boundRecipe.getType()).stream()
                .anyMatch(candidate -> candidate.id().equals(canonicalId));
        if (!supportsExactType) {
            return new ValidationResult(
                    ValidationDiagnostic.RECIPE_TYPE_MISMATCH,
                    Optional.empty());
        }

        Optional<ResolvedProcess> resolved = adapter.adapter().resolve(boundRecipe, context);
        if (resolved.isEmpty() || !resolved.orElseThrow().recipeId().equals(binding.recipeId())) {
            return rejectedNoRecipe();
        }
        var candidate = new ProcessCandidate(adapter.id(), adapter.priority(), resolved.orElseThrow());
        return withCandidateEvidence(ProcessModelValidator.validate(candidate, context), candidate);
    }

    private static ValidationResult withCandidateEvidence(ValidationResult result, ProcessCandidate candidate) {
        return result.model().isPresent() || result.rejection().isPresent() ? result
                : result.withRejection(new ProcessRejection(candidate.adapterId(),
                        candidate.process().recipeId(), "CORE_" + result.diagnostic().name()));
    }

    /** Revalidates a bounded legacy identity before saving its canonical replacement. */
    public static Optional<ProcessBinding> migrateLegacyBinding(PatternContext context, ProcessBinding binding) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(binding);
        if (!legacyMekanismId(binding.adapterId())) {
            return Optional.empty();
        }
        ValidationResult result = resolveBound(context, binding);
        return result.model().filter(model -> model.adapterId().equals(MEKANISM_ID))
                .map(model -> new ProcessBinding(binding.slot(), binding.fingerprint(),
                        MEKANISM_ID, binding.recipeId()));
    }

    public static boolean legacyMekanismId(ResourceLocation id) {
        return id.equals(mekanismAlias("mekanism_enriching"))
                || id.equals(mekanismAlias("mekanism_crushing"))
                || id.equals(mekanismAlias("mekanism_energized_smelting"));
    }

    private static ResourceLocation canonicalMekanismId(ResourceLocation id, Recipe<?> recipe) {
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        if ((id.equals(mekanismAlias("mekanism_enriching")) && mekanismType("enriching").equals(type))
                || (id.equals(mekanismAlias("mekanism_crushing")) && mekanismType("crushing").equals(type))
                || (id.equals(mekanismAlias("mekanism_energized_smelting"))
                && mekanismType("smelting").equals(type))) {
            return MEKANISM_ID;
        }
        return id;
    }

    private static ResourceLocation mekanismAlias(String path) {
        return ResourceLocation.fromNamespaceAndPath("applied_causality", path);
    }

    private static ResourceLocation mekanismType(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }

    private static ConfiguredRuleSnapshot snapshot(PatternContext context) {
        return context.level().getServer() == null ? ConfiguredRuleSnapshot.empty()
                : ConfiguredRuleService.snapshot(context.level().getServer());
    }

    private static ValidationResult rejectedNoRecipe() {
        return new ValidationResult(
                ValidationDiagnostic.NO_RECIPE,
                Optional.empty());
    }

    /**
     * Package-private pure traversal seam. Each recipe is offered only to
     * adapters indexed for its exact RecipeType.
     */
    static List<ProcessCandidate> discoverCandidates(
            BulkRecipeAdapterRegistry registry,
            RecipeSource recipeSource,
            CandidateResolver resolver) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(recipeSource, "recipeSource");
        Objects.requireNonNull(resolver, "resolver");

        List<ProcessCandidate> candidates = new ArrayList<>();
        for (RecipeType<?> recipeType : registry.indexedRecipeTypes()) {
            List<? extends Recipe<?>> recipes = Objects.requireNonNull(
                    recipeSource.recipesFor(recipeType), "recipes for " + recipeType);
            List<RegisteredProcessAdapter> adapters = registry.adaptersFor(recipeType);
            for (Recipe<?> recipe : recipes) {
                Objects.requireNonNull(recipe, "recipe");
                if (recipe.getType() != recipeType) {
                    continue;
                }
                for (RegisteredProcessAdapter adapter : adapters) {
                    resolver.resolve(adapter, recipe).ifPresent(process -> candidates.add(
                            new ProcessCandidate(adapter.id(), adapter.priority(), process)));
                }
            }
        }
        return List.copyOf(candidates);
    }

    /** Configured profiles supply a separate immutable RecipeType index, outside the Java registry. */
    static List<ProcessCandidate> discoverConfiguredCandidates(ConfiguredRuleSnapshot snapshot,
            RecipeSource recipeSource, ConfiguredCandidateResolver resolver) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(recipeSource);
        Objects.requireNonNull(resolver);
        List<ProcessCandidate> candidates = new ArrayList<>();
        for (ConfiguredRuleSnapshot.RecipeTypeProfile profile : snapshot.recipeTypes().values()) {
            RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.get(profile.recipeType());
            if (type == null || !profile.recipeType().equals(BuiltInRegistries.RECIPE_TYPE.getKey(type))) {
                continue;
            }
            for (Recipe<?> recipe : recipeSource.recipesFor(type)) {
                if (recipe.getType() != type || profile.excludedRecipes().contains(recipe.getId())) {
                    continue;
                }
                resolver.resolve(recipe, profile).ifPresent(process -> candidates.add(
                        new ProcessCandidate(ConfiguredStandardStaticItemResolver.ID, 0, process)));
            }
        }
        return List.copyOf(candidates);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<? extends Recipe<?>> recipesFor(RecipeManager recipeManager, RecipeType<?> recipeType) {
        return recipeManager.getAllRecipesFor((RecipeType) recipeType);
    }

    @FunctionalInterface
    interface RecipeSource {
        List<? extends Recipe<?>> recipesFor(RecipeType<?> recipeType);
    }

    @FunctionalInterface
    interface CandidateResolver {
        Optional<ResolvedProcess> resolve(RegisteredProcessAdapter adapter, Recipe<?> recipe);
    }

    @FunctionalInterface
    interface ConfiguredCandidateResolver {
        Optional<ResolvedProcess> resolve(Recipe<?> recipe,
                ConfiguredRuleSnapshot.RecipeTypeProfile profile);
    }

    private record CandidateValidation(ProcessCandidate candidate, ValidationResult result) {
        private CandidateValidation {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(result, "result");
        }
    }

    private record ConfiguredFailure(ValidationDiagnostic diagnostic, ProcessRejection evidence) {
        int specificity() {
            String reason = evidence.reason();
            if (reason.startsWith("RECIPE_OUTPUT_")) {
                return 0;
            }
            if (reason.startsWith("PATTERN_INPUT_")) {
                return 1;
            }
            return 2;
        }
    }
}
