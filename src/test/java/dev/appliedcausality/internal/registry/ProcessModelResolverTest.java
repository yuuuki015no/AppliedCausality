package dev.appliedcausality.internal.registry;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.api.process.IBulkRecipeAdapter;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.internal.validation.ValidationResult;
import dev.appliedcausality.internal.bulk.BulkExecutionMetrics;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBinding;
import dev.appliedcausality.internal.binding.ProcessBindingTarget;
import dev.appliedcausality.internal.binding.ProviderBindingStore;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import dev.appliedcausality.test.TestProcessingPattern;
import dev.appliedcausality.test.TestRecipeLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.crafting.conditions.ICondition;
import dev.appliedcausality.recipe.vanilla.VanillaSmeltingRecipeAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessModelResolverTest {
    private static final String TEST_PATTERN_MARKER = "applied_causality_phase7_binding_api";
    private static final IPatternDetailsDecoder TEST_PATTERN_DECODER = new IPatternDetailsDecoder() {
        @Override
        public boolean isEncodedPattern(ItemStack stack) {
            return hasTestMarker(stack.getTag());
        }

        @Override
        public IPatternDetails decodePattern(AEItemKey what, net.minecraft.world.level.Level level) {
            return what != null && hasTestMarker(what.getTag()) ? testBindingPattern(what) : null;
        }

        @Override
        public IPatternDetails decodePattern(
                ItemStack what,
                net.minecraft.world.level.Level level,
                boolean tryRecovery) {
            AEItemKey definition = AEItemKey.of(what);
            return definition != null && hasTestMarker(definition.getTag())
                    ? testBindingPattern(definition)
                    : null;
        }
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        MinecraftTestBootstrap.initialize();
        PatternDetailsHelper.registerDecoder(TEST_PATTERN_DECODER);
    }

    @Test
    void discoveryOffersEachRecipeOnlyToAdaptersIndexedForItsExactType() {
        RecipeType<Recipe<Container>> smelting = RecipeType.simple(id("smelting"));
        RecipeType<Recipe<Container>> crushing = RecipeType.simple(id("crushing"));
        var smeltingRecipe = new TestRecipe(id("smelting/one"), smelting);
        var crushingRecipe = new TestRecipe(id("crushing/one"), crushing);
        var wrongTypeFromSource = new TestRecipe(id("wrong_type"), crushing);
        var registry = new BulkRecipeAdapterRegistry();
        registry.register(adapter("smelting", smelting));
        registry.register(adapter("crushing", crushing));
        registry.freeze();
        var visited = new ArrayList<String>();

        var candidates = ProcessModelResolver.discoverCandidates(
                registry,
                type -> type == smelting
                        ? List.of(smeltingRecipe, wrongTypeFromSource)
                        : List.of(crushingRecipe),
                (adapter, recipe) -> {
                    visited.add(adapter.id() + "->" + recipe.getId());
                    return Optional.of(process(recipe));
                });

        assertEquals(List.of(
                "test:smelting->test:smelting/one",
                "test:crushing->test:crushing/one"), visited);
        assertEquals(2, candidates.size());
    }

    @Test
    void eventRegisteredGroupedAdapterIsInvokedOnlyForItsCurrentDeclaredType() {
        RecipeType<Recipe<Container>> first = RecipeType.simple(id("first"));
        RecipeType<Recipe<Container>> second = RecipeType.simple(id("second"));
        RecipeType<Recipe<Container>> outside = RecipeType.simple(id("outside"));
        var firstRecipe = new TestRecipe(id("first/one"), first);
        var secondRecipe = new TestRecipe(id("second/one"), second);
        var outsideRecipe = new TestRecipe(id("outside/one"), outside);
        var registry = new BulkRecipeAdapterRegistry();
        var grouped = new IBulkRecipeAdapter() {
            @Override
            public ResourceLocation id() {
                return ProcessModelResolverTest.id("grouped");
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public List<RecipeType<?>> supportedRecipeTypes() {
                return List.of(first, second);
            }

            @Override
            public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
                return Optional.of(process(recipe));
            }
        };
        ProcessAdapterRegistrationLifecycle.dispatchAndFreeze(registry, event -> event.register(grouped));
        var visited = new ArrayList<ResourceLocation>();

        var candidates = ProcessModelResolver.discoverCandidates(registry,
                type -> type == first ? List.of(firstRecipe, outsideRecipe) : List.of(secondRecipe),
                (adapter, recipe) -> {
                    visited.add(recipe.getId());
                    return Optional.of(process(recipe));
                });

        assertEquals(List.of(firstRecipe.getId(), secondRecipe.getId()), visited);
        assertEquals(2, candidates.size());
        assertEquals(List.of(grouped.id(), grouped.id()),
                candidates.stream().map(ProcessCandidate::adapterId).toList());
    }

    @Test
    void publicResolveUsesRecipeManagerSelectionAndMandatoryValidation() {
        var recipe = new SmeltingRecipe(
                id("iron"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        var recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(recipe));
        var inputKey = AEItemKey.of(Items.IRON_ORE);
        var outputKey = AEItemKey.of(Items.IRON_INGOT);
        var pattern = TestProcessingPattern.create(
                AEItemKey.of(Items.STONE),
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(inputKey, 1L)}, 1L)},
                new GenericStack[]{new GenericStack(outputKey, 1L)});
        var registry = ProcessAdapterRegistrationLifecycle.registry();
        if (!registry.isFrozen()) {
            ProcessAdapterRegistrationLifecycle.registerBundledAdapters(registry);
            registry.freeze();
        }

        ValidationResult result;
        ValidationResult rejected;
        BulkExecutionMetrics.Snapshot metrics;
        try (var recording = BulkExecutionMetrics.begin()) {
            result = ProcessModelResolver.resolve(new PatternContext(
                    TestRecipeLevel.create(recipeManager), pattern));

            var mismatchedPattern = TestProcessingPattern.create(
                    new IPatternDetails.IInput[]{new TestInput(
                            new GenericStack[]{new GenericStack(inputKey, 1L)}, 1L)},
                    new GenericStack[]{new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 1L)});
            rejected = ProcessModelResolver.resolve(new PatternContext(
                    TestRecipeLevel.create(recipeManager), mismatchedPattern));
            metrics = recording.snapshot();
        }

        assertEquals(ValidationDiagnostic.VALID, result.diagnostic());
        assertTrue(result.model().isPresent());
        assertEquals(recipe.getId(), result.model().orElseThrow().recipeId());

        assertEquals(ValidationDiagnostic.OUTPUT_MISMATCH, rejected.diagnostic());
        assertTrue(rejected.model().isEmpty());
        assertEquals(2, metrics.recipeResolutions());
        assertEquals(0, metrics.providerLookups());
    }

    @Test
    void resolveSelectsValidRecipeWhenAnotherCandidateHasWrongOutput() {
        var matching = new SmeltingRecipe(
                id("matching"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        var wrongOutput = new SmeltingRecipe(
                id("wrong_output"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.GOLD_INGOT), 0.0F, 200);
        var recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(matching, wrongOutput));
        var pattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1L)}, 1L)},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)});
        var registry = new BulkRecipeAdapterRegistry();
        registry.register(new VanillaSmeltingRecipeAdapter());
        registry.freeze();

        var result = ProcessModelResolver.resolve(
                registry, new PatternContext(TestRecipeLevel.create(recipeManager), pattern));

        assertEquals(ValidationDiagnostic.VALID, result.diagnostic());
        assertEquals(matching.getId(), result.model().orElseThrow().recipeId());
    }

    @Test
    void resolveIgnoresStaleCandidateWhenAnotherCandidateIsValid() {
        var recipe = new SmeltingRecipe(
                id("authoritative"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        var recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(recipe));
        var pattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1L)}, 1L)},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)});
        var registry = new BulkRecipeAdapterRegistry();
        registry.register(new VanillaSmeltingRecipeAdapter());
        registry.register(resolvingAdapter("stale", 10, ignored -> Optional.of(process(
                id("missing"), id("stale_family"), id("stale_machine"),
                Items.IRON_ORE, Items.IRON_INGOT, new ProcessProperties(true, true, true, true)))));
        registry.freeze();

        var result = ProcessModelResolver.resolve(
                registry, new PatternContext(TestRecipeLevel.create(recipeManager), pattern));

        assertEquals(ValidationDiagnostic.VALID, result.diagnostic());
        assertEquals(recipe.getId(), result.model().orElseThrow().recipeId());
    }

    @Test
    void resolveIgnoresInputMismatchedCandidateWhenAnotherCandidateIsValid() {
        var recipe = new SmeltingRecipe(
                id("authoritative_input"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        var recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(recipe));
        var pattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1L)}, 1L)},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)});
        var registry = new BulkRecipeAdapterRegistry();
        registry.register(new VanillaSmeltingRecipeAdapter());
        registry.register(resolvingAdapter("input_mismatch", 10, ignored -> Optional.of(new ResolvedProcess(
                recipe.getId(),
                ResourceLocation.withDefaultNamespace("smelting"),
                ProcessShapeId.of("test", "item_to_item"),
                id("input_mismatch_family"),
                id("input_mismatch_machine"),
                Map.of(AEItemKey.of(Items.IRON_ORE), 2L),
                Map.of(AEItemKey.of(Items.IRON_INGOT), 1L),
                Map.of(),
                new ProcessProperties(true, true, true, true)))));
        registry.freeze();

        var result = ProcessModelResolver.resolve(
                registry, new PatternContext(TestRecipeLevel.create(recipeManager), pattern));

        assertEquals(ValidationDiagnostic.VALID, result.diagnostic());
        assertEquals(recipe.getId(), result.model().orElseThrow().recipeId());
    }

    @Test
    void resolveReturnsHigherPriorityVerifiedModelForIdenticalValidSemantics() {
        var recipe = new SmeltingRecipe(
                id("identical"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        var recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(recipe));
        var pattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1L)}, 1L)},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)});
        ResourceLocation higherPriorityAdapterId = id("identical_high_priority");
        var registry = new BulkRecipeAdapterRegistry();
        registry.register(new VanillaSmeltingRecipeAdapter());
        registry.register(resolvingAdapter(
                higherPriorityAdapterId.getPath(),
                10,
                ignored -> Optional.of(new ResolvedProcess(
                        recipe.getId(),
                        ResourceLocation.withDefaultNamespace("smelting"),
                        ProcessShapeId.of("applied_causality", "item_to_item"),
                        ResourceLocation.withDefaultNamespace("smelting"),
                        ResourceLocation.withDefaultNamespace("furnace"),
                        Map.of(AEItemKey.of(Items.IRON_ORE), 1L),
                        Map.of(AEItemKey.of(Items.IRON_INGOT), 1L),
                        Map.of(),
                        new ProcessProperties(true, true, true, true)))));
        registry.freeze();

        var result = ProcessModelResolver.resolve(
                registry, new PatternContext(TestRecipeLevel.create(recipeManager), pattern));

        assertEquals(ValidationDiagnostic.VALID, result.diagnostic());
        assertEquals(higherPriorityAdapterId, result.model().orElseThrow().adapterId());
        assertEquals(recipe.getId(), result.model().orElseThrow().recipeId());
    }

    @Test
    void resolveKeepsDifferentIndividuallyValidSemanticsAmbiguous() {
        var recipe = new SmeltingRecipe(
                id("shared"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        var recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(recipe));
        var pattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1L)}, 1L)},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)});
        var registry = new BulkRecipeAdapterRegistry();
        registry.register(new VanillaSmeltingRecipeAdapter());
        registry.register(resolvingAdapter("alternate", 10, ignored -> Optional.of(process(
                recipe.getId(), id("alternate_family"), id("alternate_machine"),
                Items.IRON_ORE, Items.IRON_INGOT, new ProcessProperties(true, true, true, true)))));
        registry.freeze();

        var context = new PatternContext(TestRecipeLevel.create(recipeManager), pattern);
        var result = ProcessModelResolver.resolve(registry, context);
        var targets = ProcessModelResolver.findBindingTargets(registry, context);

        assertEquals(ValidationDiagnostic.AMBIGUOUS_RECIPE, result.diagnostic());
        assertTrue(result.model().isEmpty());
        assertEquals(List.of(
                new ProcessBindingTarget(id("alternate"), recipe.getId()),
                new ProcessBindingTarget(VanillaSmeltingRecipeAdapter.ID, recipe.getId())), targets);
    }

    @Test
    void zeroValidCandidatesReturnFirstStableConcreteDiagnostic() {
        var low = new ProcessCandidate(id("low"), 0, process(
                id("low_recipe"), id("family"), id("machine"),
                Items.IRON_ORE, Items.GOLD_INGOT, new ProcessProperties(true, true, true, true)));
        var high = new ProcessCandidate(id("high"), 10, process(
                id("high_recipe"), id("family"), id("machine"),
                Items.IRON_ORE, Items.IRON_INGOT, new ProcessProperties(false, true, true, true)));

        var result = ProcessModelResolver.selectValidatedCandidates(
                List.of(low, high),
                candidate -> new ValidationResult(
                        candidate == high
                                ? ValidationDiagnostic.NON_DETERMINISTIC
                                : ValidationDiagnostic.OUTPUT_MISMATCH,
                        Optional.empty()));

        assertEquals(ValidationDiagnostic.NON_DETERMINISTIC, result.diagnostic());
        assertTrue(result.model().isEmpty());
        assertEquals(high.process().recipeId(), result.rejection().orElseThrow().recipeId());
        assertEquals("CORE_NON_DETERMINISTIC", result.rejection().orElseThrow().reason());
    }

    @Test
    void boundResolveRevalidatesSavedRecipeIdentityAgainstCurrentRecipeManager() {
        var recipe = new SmeltingRecipe(
                id("iron"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        var recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(recipe));
        var pattern = TestProcessingPattern.create(
                AEItemKey.of(Items.STONE),
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1L)}, 1L)},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)});
        var context = new PatternContext(TestRecipeLevel.create(recipeManager), pattern);
        var registry = new BulkRecipeAdapterRegistry();
        registry.register(new VanillaSmeltingRecipeAdapter());
        registry.freeze();
        var fingerprint = PatternFingerprint.fromDefinition(new ItemStack(Items.STONE));

        var valid = ProcessModelResolver.resolveBound(
                registry,
                context,
                new ProcessBinding(0, fingerprint, VanillaSmeltingRecipeAdapter.ID, recipe.getId()));
        var staleRecipe = ProcessModelResolver.resolveBound(
                registry,
                context,
                new ProcessBinding(0, fingerprint, VanillaSmeltingRecipeAdapter.ID, id("removed_recipe")));
        var staleAdapter = ProcessModelResolver.resolveBound(
                registry,
                context,
                new ProcessBinding(0, fingerprint, id("removed_adapter"), recipe.getId()));
        var replacementPattern = TestProcessingPattern.create(
                AEItemKey.of(Items.DIRT),
                pattern.getInputs(),
                pattern.getOutputs());
        var fingerprintMismatch = ProcessModelResolver.resolveBound(
                registry,
                new PatternContext(TestRecipeLevel.create(recipeManager), replacementPattern),
                new ProcessBinding(0, fingerprint, VanillaSmeltingRecipeAdapter.ID, recipe.getId()));
        var changedRecipe = new SmeltingRecipe(
                recipe.getId(), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.GOLD_INGOT), 0.0F, 200);
        recipeManager.replaceRecipes(List.of(changedRecipe));
        var changedRecipeSemantics = ProcessModelResolver.resolveBound(
                registry,
                context,
                new ProcessBinding(0, fingerprint, VanillaSmeltingRecipeAdapter.ID, recipe.getId()));

        assertEquals(ValidationDiagnostic.VALID, valid.diagnostic());
        assertEquals(recipe.getId(), valid.model().orElseThrow().recipeId());
        assertEquals(ValidationDiagnostic.NO_RECIPE, staleRecipe.diagnostic());
        assertTrue(staleRecipe.model().isEmpty());
        assertEquals(ValidationDiagnostic.NO_RECIPE, staleAdapter.diagnostic());
        assertTrue(staleAdapter.model().isEmpty());
        assertEquals(ValidationDiagnostic.PATTERN_FINGERPRINT_MISMATCH, fingerprintMismatch.diagnostic());
        assertTrue(fingerprintMismatch.model().isEmpty());
        assertEquals(ValidationDiagnostic.OUTPUT_MISMATCH, changedRecipeSemantics.diagnostic());
        assertTrue(changedRecipeSemantics.model().isEmpty());
    }

    @Test
    void providerBindingMutationApiValidatesPersistsAndClears() throws ReflectiveOperationException {
        var recipe = new SmeltingRecipe(
                id("binding_api"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        var alternativeRecipe = new SmeltingRecipe(
                id("binding_api_alternative"), "", CookingBookCategory.MISC,
                Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT), 0.0F, 200);
        var recipeManager = new RecipeManager(ICondition.IContext.EMPTY);
        recipeManager.replaceRecipes(List.of(recipe, alternativeRecipe));
        var registry = ProcessAdapterRegistrationLifecycle.registry();
        if (!registry.isFrozen()) {
            ProcessAdapterRegistrationLifecycle.registerBundledAdapters(registry);
            registry.freeze();
        }
        var provider = createProvider();
        ItemStack definition = new ItemStack(Items.BARRIER);
        definition.getOrCreateTag().putBoolean(TEST_PATTERN_MARKER, true);
        CompoundTag originalPatternTag = definition.getTag().copy();
        provider.getInternalInventory().setItemDirect(0, definition);
        provider.getInternalInventory().setItemDirect(1, definition.copy());
        provider.setLevel(TestRecipeLevel.create(recipeManager));

        assertFalse(provider.trySetProcessBinding(
                0, VanillaSmeltingRecipeAdapter.ID, id("missing_binding_api_recipe")));
        assertTrue(provider.trySetProcessBinding(0, VanillaSmeltingRecipeAdapter.ID, recipe.getId()));
        assertFalse(provider.trySetProcessBinding(
                1, VanillaSmeltingRecipeAdapter.ID, alternativeRecipe.getId()));
        assertTrue(provider.trySetProcessBinding(1, VanillaSmeltingRecipeAdapter.ID, recipe.getId()));
        CompoundTag boundData = new CompoundTag();
        provider.saveAdditional(boundData);
        ProviderBindingStore boundStore = loadBindingStore(boundData);
        assertEquals(recipe.getId(), boundStore.matching(
                0, PatternFingerprint.fromDefinition(definition)).orElseThrow().recipeId());
        assertEquals(recipe.getId(), boundStore.matching(
                1, PatternFingerprint.fromDefinition(definition)).orElseThrow().recipeId());

        assertTrue(provider.clearProcessBinding(0));
        assertFalse(provider.clearProcessBinding(0));
        assertTrue(provider.clearProcessBinding(1));
        CompoundTag clearedData = new CompoundTag();
        provider.saveAdditional(clearedData);
        assertEquals(0, loadBindingStore(clearedData).size());
        assertEquals(originalPatternTag, provider.getInternalInventory().getStackInSlot(0).getTag());
    }

    private static IBulkRecipeAdapter adapter(String path, RecipeType<?> type) {
        return new IBulkRecipeAdapter() {
            @Override
            public ResourceLocation id() {
                return ProcessModelResolverTest.id(path);
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public List<RecipeType<?>> supportedRecipeTypes() {
                return List.of(type);
            }

            @Override
            public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
                throw new AssertionError("pure discovery seam should supply the resolver");
            }
        };
    }

    private static IBulkRecipeAdapter resolvingAdapter(
            String path,
            int priority,
            Function<Recipe<?>, Optional<ResolvedProcess>> resolver) {
        return new IBulkRecipeAdapter() {
            @Override
            public ResourceLocation id() {
                return ProcessModelResolverTest.id(path);
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public List<RecipeType<?>> supportedRecipeTypes() {
                return List.of(RecipeType.SMELTING);
            }

            @Override
            public Optional<ResolvedProcess> resolve(Recipe<?> recipe, PatternContext context) {
                return resolver.apply(recipe);
            }
        };
    }

    private static ResolvedProcess process(
            ResourceLocation recipeId,
            ResourceLocation family,
            ResourceLocation machine,
            net.minecraft.world.item.Item input,
            net.minecraft.world.item.Item output,
            ProcessProperties properties) {
        return new ResolvedProcess(
                recipeId,
                ResourceLocation.withDefaultNamespace("smelting"),
                ProcessShapeId.of("test", "item_to_item"),
                family,
                machine,
                Map.of(AEItemKey.of(input), 1L),
                Map.of(AEItemKey.of(output), 1L),
                Map.of(),
                properties);
    }

    private static ResolvedProcess process(Recipe<?> recipe) {
        return new ResolvedProcess(
                recipe.getId(), id("type"), ProcessShapeId.of("test", "shape"), id("family"), null,
                Map.of(), Map.of(), Map.of(), new ProcessProperties(true, true, true, true));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private static SingularPatternProviderBlockEntity createProvider() throws ReflectiveOperationException {
        BlockEntityType<SingularPatternProviderBlockEntity> type =
                BlockEntityType.Builder.<SingularPatternProviderBlockEntity>of(
                        (pos, state) -> {
                            throw new UnsupportedOperationException("test factory is not used");
                        },
                        Blocks.IRON_BLOCK).build(null);
        var constructor = SingularPatternProviderBlockEntity.class.getDeclaredConstructor(
                BlockEntityType.class, BlockPos.class, net.minecraft.world.level.block.state.BlockState.class);
        constructor.setAccessible(true);
        return constructor.newInstance(type, BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState());
    }

    private static ProviderBindingStore loadBindingStore(CompoundTag providerData) {
        return ProviderBindingStore.load(
                providerData.getCompound("applied_causality").getCompound("bindings"),
                SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
    }

    private static boolean hasTestMarker(CompoundTag tag) {
        return tag != null && tag.getBoolean(TEST_PATTERN_MARKER);
    }

    private static TestProcessingPattern testBindingPattern(AEItemKey definition) {
        return TestProcessingPattern.create(
                definition,
                new IPatternDetails.IInput[]{new TestInput(
                        new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1L)}, 1L)},
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)});
    }

    private record TestRecipe(ResourceLocation id, RecipeType<?> type) implements Recipe<Container> {
        @Override
        public boolean matches(Container container, net.minecraft.world.level.Level level) {
            return false;
        }

        @Override
        public ItemStack assemble(Container container, RegistryAccess registryAccess) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return false;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess registryAccess) {
            return ItemStack.EMPTY;
        }

        @Override
        public ResourceLocation getId() {
            return id;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return null;
        }

        @Override
        public RecipeType<?> getType() {
            return type;
        }
    }

    private record TestInput(GenericStack[] possibleInputs, long multiplier) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return input.equals(possibleInputs[0].what());
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
