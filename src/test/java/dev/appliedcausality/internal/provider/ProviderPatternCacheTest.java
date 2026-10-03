package dev.appliedcausality.internal.provider;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.util.inv.AppEngInternalInventory;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBinding;
import dev.appliedcausality.internal.binding.ProviderBindingStore;
import dev.appliedcausality.internal.config.ConfiguredRuleSnapshot;
import dev.appliedcausality.internal.config.ConfiguredUnlockPolicy;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.internal.validation.ValidationResult;
import dev.appliedcausality.internal.validation.ProcessRejection;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderPatternCacheTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void advertisesOnlyVerifiedDecodedPatternsAndKeepsDiagnostics() {
        var verifiedPattern = pattern("verified");
        var rejectedPattern = pattern("rejected");
        var verifiedModel = verifiedModel("verified_recipe");
        var inventory = new appeng.util.inv.AppEngInternalInventory(4);
        inventory.setItemDirect(0, new ItemStack(Items.STONE));
        inventory.setItemDirect(1, new ItemStack(Items.DIRT));
        inventory.setItemDirect(2, new ItemStack(Items.APPLE));

        var cache = ProviderPatternCache.rebuild(
                inventory,
                stack -> {
                    if (stack.is(Items.STONE)) {
                        return verifiedPattern;
                    }
                    if (stack.is(Items.DIRT)) {
                        return rejectedPattern;
                    }
                    return null;
                },
                candidate -> candidate == verifiedPattern
                        ? new ValidationResult(ValidationDiagnostic.VALID, Optional.of(verifiedModel))
                        : new ValidationResult(ValidationDiagnostic.NO_RECIPE, Optional.empty()));

        assertEquals(List.of(verifiedPattern), cache.advertisedPatterns());
        assertSame(verifiedModel, cache.verifiedModel(verifiedPattern).orElseThrow());
        assertFalse(cache.verifiedModel(rejectedPattern).isPresent());
        assertEquals(List.of(
                new ProviderPatternStatus(0, ValidationDiagnostic.VALID, id("verified_recipe")),
                new ProviderPatternStatus(1, ValidationDiagnostic.NO_RECIPE, null),
                new ProviderPatternStatus(2, ValidationDiagnostic.UNSUPPORTED_PATTERN, null)),
                cache.statuses());
    }

    @Test
    void rejectedRecipeEvidenceSurvivesInDerivedStatusWithoutBecomingExecutable() {
        var pattern = pattern("rejected_evidence");
        var inventory = new AppEngInternalInventory(1);
        inventory.setItemDirect(0, new ItemStack(Items.STONE));
        var evidence = new ProcessRejection(id("configured_adapter"), id("tagged_output"),
                "RECIPE_OUTPUT_NBT");
        var cache = ProviderPatternCache.rebuild(inventory, ignored -> pattern,
                ignored -> new ValidationResult(ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED,
                        Optional.empty()).withRejection(evidence));

        assertTrue(cache.advertisedPatterns().isEmpty());
        assertTrue(cache.verifiedModel(pattern).isEmpty());
        assertSame(evidence, cache.statuses().get(0).rejection());
    }

    @Test
    void unlockAppliesOnlyAfterValidationAndChangesAdvertisement() {
        var pattern = pattern("gated");
        var invalidPattern = pattern("invalid");
        var model = verifiedModel("gated_recipe");
        var grant = id("grant");
        var rules = new ConfiguredRuleSnapshot(1, Map.of(), Map.of(), java.util.Set.of(grant),
                Map.of(id("gate"), new ConfiguredRuleSnapshot.UnlockRule(id("gate"),
                        model.recipeId(), null, null, null, null, null, List.of(grant))), false);
        var inventory = new AppEngInternalInventory(2);
        inventory.setItemDirect(0, new ItemStack(Items.STONE));
        inventory.setItemDirect(1, new ItemStack(Items.DIRT));
        ProviderPatternCache.PatternDecoder decoder = stack -> stack.is(Items.STONE) ? pattern : invalidPattern;
        ProviderPatternCache.PatternResolver resolver = candidate -> candidate == pattern
                ? new ValidationResult(ValidationDiagnostic.VALID, Optional.of(model))
                : new ValidationResult(ValidationDiagnostic.OUTPUT_MISMATCH, Optional.empty());
        ProviderBindingStore bindings = ProviderBindingStore.empty(2);
        ProviderPatternCache.BoundPatternResolver bound = (ignored, candidate) -> resolver.resolve(candidate);

        var locked = ProviderPatternCache.rebuild(1, inventory, decoder, resolver, bindings, bound,
                candidate -> ConfiguredUnlockPolicy.allows(rules, candidate, java.util.Set.of()), true);
        assertTrue(locked.advertisedPatterns().isEmpty());
        assertTrue(locked.verifiedModel(pattern).isEmpty());
        assertEquals(ValidationDiagnostic.LOCKED, locked.statuses().get(0).diagnostic());
        assertEquals(ValidationDiagnostic.OUTPUT_MISMATCH, locked.statuses().get(1).diagnostic());

        var available = ProviderPatternCache.rebuild(1, inventory, decoder, resolver, bindings, bound,
                candidate -> ConfiguredUnlockPolicy.allows(rules, candidate, java.util.Set.of(grant)), true);
        assertEquals(List.of(pattern), available.advertisedPatterns());
        assertEquals(ValidationDiagnostic.AVAILABLE, available.statuses().get(0).diagnostic());
        assertEquals(ValidationDiagnostic.OUTPUT_MISMATCH, available.statuses().get(1).diagnostic());
        assertFalse(locked.hasSameStatusAndAvailability(available));
    }

    @Test
    void rebuiltSnapshotUsesRefreshedModelForEquivalentPatternDefinition() {
        var oldPattern = pattern("same_definition");
        var replacementPattern = pattern("same_definition");
        var unrelatedPattern = pattern("unrelated");
        var oldModel = verifiedModel("old_recipe");
        var refreshedModel = verifiedModel("refreshed_recipe");
        var inventory = new appeng.util.inv.AppEngInternalInventory(1);
        inventory.setItemDirect(0, new ItemStack(Items.STONE));

        var oldCache = ProviderPatternCache.rebuild(
                inventory,
                ignored -> oldPattern,
                ignored -> new ValidationResult(ValidationDiagnostic.VALID, Optional.of(oldModel)));
        var rebuiltCache = ProviderPatternCache.rebuild(
                inventory,
                ignored -> replacementPattern,
                ignored -> new ValidationResult(ValidationDiagnostic.VALID, Optional.of(refreshedModel)));

        assertSame(oldModel, oldCache.verifiedModel(oldPattern).orElseThrow());
        assertSame(refreshedModel, rebuiltCache.verifiedModel(oldPattern).orElseThrow());
        assertSame(refreshedModel, rebuiltCache.verifiedModel(replacementPattern).orElseThrow());
        assertFalse(rebuiltCache.verifiedModel(unrelatedPattern).isPresent());
        assertThrows(UnsupportedOperationException.class,
                () -> rebuiltCache.advertisedPatterns().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> rebuiltCache.statuses().clear());
    }

    @Test
    void generationMismatchHidesAdvertisementStatusesAndExecutableModel() {
        var inventory = new AppEngInternalInventory(1);
        inventory.setItemDirect(0, new ItemStack(Items.STONE));
        var pattern = pattern("generation_guard");
        var cache = ProviderPatternCache.rebuild(7, inventory, ignored -> pattern,
                ignored -> new ValidationResult(ValidationDiagnostic.VALID,
                        Optional.of(verifiedModel("generation_guard"))),
                ProviderBindingStore.empty(1), (binding, ignored) -> {
                    throw new AssertionError("No binding expected");
                });

        assertEquals(List.of(pattern), cache.advertisedPatterns(7));
        assertTrue(cache.verifiedModel(pattern, 7).isPresent());
        assertTrue(cache.advertisedPatterns(8).isEmpty());
        assertTrue(cache.statuses(8).isEmpty());
        assertTrue(cache.verifiedModel(pattern, 8).isEmpty());
    }

    @Test
    void emptySnapshotAuthorizesNothing() {
        var cache = ProviderPatternCache.empty();

        assertTrue(cache.advertisedPatterns().isEmpty());
        assertTrue(cache.statuses().isEmpty());
        assertTrue(cache.verifiedModel(pattern("empty")).isEmpty());
    }

    @Test
    void statusAndAvailabilityComparisonExcludesRebuiltModelIdentity() {
        var inventory = new AppEngInternalInventory(1);
        inventory.setItemDirect(0, new ItemStack(Items.STONE));
        var decoded = pattern("same_definition");
        var first = ProviderPatternCache.rebuild(
                inventory,
                ignored -> decoded,
                ignored -> new ValidationResult(
                        ValidationDiagnostic.VALID, Optional.of(verifiedModel("same_recipe"))));
        var rebuilt = ProviderPatternCache.rebuild(
                inventory,
                ignored -> pattern("same_definition"),
                ignored -> new ValidationResult(
                        ValidationDiagnostic.VALID, Optional.of(verifiedModel("same_recipe"))));
        var rejected = ProviderPatternCache.rebuild(
                inventory,
                ignored -> pattern("same_definition"),
                ignored -> new ValidationResult(ValidationDiagnostic.NO_RECIPE, Optional.empty()));

        assertTrue(first.hasSameStatusAndAvailability(rebuilt),
                "fresh derived model identity alone must not churn the provider advertisement");
        assertFalse(first.hasSameStatusAndAvailability(rejected));
        assertFalse(ProviderPatternCache.empty().hasSameStatusAndAvailability(rejected),
                "slot diagnostics are observable even when neither snapshot advertises a pattern");
    }

    @Test
    void matchingBindingUsesBoundRevalidationOnEveryRebuild() {
        var inventory = new AppEngInternalInventory(1);
        ItemStack definition = new ItemStack(Items.STONE);
        inventory.setItemDirect(0, definition);
        var decoded = pattern("bound_definition");
        var binding = new ProcessBinding(
                0, PatternFingerprint.fromDefinition(definition), id("adapter"), id("bound_recipe"));
        var bindings = ProviderBindingStore.empty(1);
        bindings.put(binding);
        var autoCalls = new AtomicInteger();
        var boundCalls = new AtomicInteger();

        var first = ProviderPatternCache.rebuild(
                inventory,
                ignored -> decoded,
                ignored -> {
                    autoCalls.incrementAndGet();
                    return new ValidationResult(ValidationDiagnostic.NO_RECIPE, Optional.empty());
                },
                bindings,
                (selected, ignored) -> {
                    assertEquals(binding, selected);
                    boundCalls.incrementAndGet();
                    return new ValidationResult(
                            ValidationDiagnostic.VALID, Optional.of(verifiedModel("bound_recipe")));
                });
        var afterRecipeReload = ProviderPatternCache.rebuild(
                inventory,
                ignored -> decoded,
                ignored -> {
                    autoCalls.incrementAndGet();
                    return new ValidationResult(ValidationDiagnostic.NO_RECIPE, Optional.empty());
                },
                bindings,
                (selected, ignored) -> {
                    boundCalls.incrementAndGet();
                    return new ValidationResult(ValidationDiagnostic.NO_RECIPE, Optional.empty());
                });

        assertEquals(0, autoCalls.get());
        assertEquals(2, boundCalls.get(), "a recipe reload rebuild must revalidate persisted identity");
        assertEquals(1, first.advertisedPatterns().size());
        assertTrue(afterRecipeReload.advertisedPatterns().isEmpty());
        assertEquals(ValidationDiagnostic.NO_RECIPE, afterRecipeReload.statuses().get(0).diagnostic());
    }

    @Test
    void slotMatchWithoutFingerprintMatchFallsBackToAutomaticResolution() {
        var inventory = new AppEngInternalInventory(1);
        inventory.setItemDirect(0, new ItemStack(Items.DIRT));
        var replacementPattern = pattern("replacement");
        var binding = new ProcessBinding(
                0,
                PatternFingerprint.fromDefinition(new ItemStack(Items.STONE)),
                id("adapter"),
                id("old_recipe"));
        var bindings = ProviderBindingStore.empty(1);
        bindings.put(binding);
        var boundCalls = new AtomicInteger();

        var cache = ProviderPatternCache.rebuild(
                inventory,
                ignored -> replacementPattern,
                ignored -> new ValidationResult(
                        ValidationDiagnostic.VALID, Optional.of(verifiedModel("replacement_recipe"))),
                bindings,
                (selected, ignored) -> {
                    boundCalls.incrementAndGet();
                    return new ValidationResult(ValidationDiagnostic.NO_RECIPE, Optional.empty());
                });

        assertEquals(0, boundCalls.get());
        assertEquals(id("replacement_recipe"), cache.statuses().get(0).recipeId());
    }

    @Test
    void conflictingBindingsForEqualPatternDefinitionsAuthorizeNoModel() {
        var inventory = new AppEngInternalInventory(2);
        ItemStack definition = new ItemStack(Items.STONE);
        inventory.setItemDirect(0, definition.copy());
        inventory.setItemDirect(1, definition.copy());
        PatternFingerprint fingerprint = PatternFingerprint.fromDefinition(definition);
        var bindings = ProviderBindingStore.empty(2);
        bindings.put(new ProcessBinding(0, fingerprint, id("adapter"), id("recipe_a")));
        bindings.put(new ProcessBinding(1, fingerprint, id("adapter"), id("recipe_b")));
        var boundCalls = new AtomicInteger();

        var cache = ProviderPatternCache.rebuild(
                inventory,
                ignored -> pattern("same_definition"),
                ignored -> {
                    throw new AssertionError("automatic resolution must not select between conflicting bindings");
                },
                bindings,
                (binding, ignored) -> {
                    boundCalls.incrementAndGet();
                    return new ValidationResult(
                            ValidationDiagnostic.VALID,
                            Optional.of(verifiedModel(binding.recipeId().getPath())));
                });

        assertEquals(0, boundCalls.get());
        assertTrue(cache.advertisedPatterns().isEmpty());
        assertTrue(cache.verifiedModel(pattern("same_definition")).isEmpty());
        assertEquals(List.of(
                new ProviderPatternStatus(0, ValidationDiagnostic.CONFLICTING_BINDING, null),
                new ProviderPatternStatus(1, ValidationDiagnostic.CONFLICTING_BINDING, null)),
                cache.statuses());
    }

    @Test
    void equalDefinitionsWithTheSameBindingResolveOnceAndAdvertiseOnce() {
        var inventory = new AppEngInternalInventory(2);
        ItemStack definition = new ItemStack(Items.STONE);
        inventory.setItemDirect(0, definition.copy());
        inventory.setItemDirect(1, definition.copy());
        PatternFingerprint fingerprint = PatternFingerprint.fromDefinition(definition);
        var bindings = ProviderBindingStore.empty(2);
        bindings.put(new ProcessBinding(0, fingerprint, id("adapter"), id("recipe")));
        bindings.put(new ProcessBinding(1, fingerprint, id("adapter"), id("recipe")));
        var boundCalls = new AtomicInteger();

        var cache = ProviderPatternCache.rebuild(
                inventory,
                ignored -> pattern("same_definition"),
                ignored -> {
                    throw new AssertionError("matching persisted bindings must use bound resolution");
                },
                bindings,
                (binding, ignored) -> {
                    boundCalls.incrementAndGet();
                    return new ValidationResult(
                            ValidationDiagnostic.VALID, Optional.of(verifiedModel("recipe")));
                });

        assertEquals(1, boundCalls.get());
        assertEquals(1, cache.advertisedPatterns().size());
        assertEquals(id("recipe"), cache.verifiedModel(pattern("same_definition")).orElseThrow().recipeId());
        assertEquals(List.of(
                new ProviderPatternStatus(0, ValidationDiagnostic.VALID, id("recipe")),
                new ProviderPatternStatus(1, ValidationDiagnostic.VALID, id("recipe"))),
                cache.statuses());
    }

    @Test
    void providerInventoryAcceptsOnlyOneExpectedPatternItemPerSlot() {
        var inventory = new AppEngInternalInventory(
                null, 1, 1, ProcessingPatternItemFilter.forItem(Items.STONE));

        assertTrue(inventory.isItemValid(0, new ItemStack(Items.STONE)));
        assertFalse(inventory.isItemValid(0, new ItemStack(Items.DIRT)));
        assertEquals(1, inventory.getSlotLimit(0));
    }

    private static IPatternDetails pattern(String definitionId) {
        return new TestPattern(definitionId);
    }

    private static VerifiedProcessModel verifiedModel(String recipePath) {
        try {
            Constructor<VerifiedProcessModel> constructor = VerifiedProcessModel.class.getDeclaredConstructor(
                    ResourceLocation.class,
                    ResourceLocation.class,
                    ResourceLocation.class,
                    ProcessShapeId.class,
                    ResourceLocation.class,
                    ResourceLocation.class,
                    Map.class,
                    Map.class,
                    Map.class,
                    ProcessProperties.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    id("adapter"),
                    id(recipePath),
                    ResourceLocation.withDefaultNamespace("smelting"),
                    ProcessShapeId.of("test", "item_to_item"),
                    id("family"),
                    null,
                    Map.<AEKey, Long>of(),
                    Map.<AEKey, Long>of(),
                    Map.<AEKey, Long>of(),
                    new ProcessProperties(true, true, true, true));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to create test-only verified model", exception);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private record TestPattern(String definitionId) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return new IInput[0];
        }

        @Override
        public GenericStack[] getOutputs() {
            return new GenericStack[0];
        }
    }
}
