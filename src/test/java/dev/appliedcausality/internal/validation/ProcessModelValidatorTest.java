package dev.appliedcausality.internal.validation;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AECraftingPattern;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import dev.appliedcausality.test.TestProcessingPattern;
import dev.appliedcausality.test.UnsafeAllocator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessModelValidatorTest {
    private static final AEKey INPUT = new TestKey("input");
    private static final AEKey OUTPUT = new TestKey("output");

    @BeforeAll
    static void bootstrapMinecraft() {
        dev.appliedcausality.test.MinecraftTestBootstrap.initialize();
    }

    @Test
    void fluidPatternUsesExactMilliBucketsAndDistinctTags() {
        var water = AEFluidKey.of(Fluids.WATER);
        var lava = AEFluidKey.of(Fluids.LAVA);
        assertEquals(water, AEFluidKey.of(new FluidStack(Fluids.WATER, 500)));
        assertEquals(water, AEFluidKey.of(new FluidStack(Fluids.WATER, 2_000)));
        var process = process(new ProcessProperties(true, true, true, true),
                Map.of(water, 3_500L), Map.of(lava, 750L), Map.of());
        var pattern = TestProcessingPattern.create(new IPatternDetails.IInput[]{
                new TestInput(new GenericStack[]{new GenericStack(water, 500L)}, 3L, null),
                new TestInput(new GenericStack[]{new GenericStack(water, 2_000L)}, 1L, null)
        }, new GenericStack[]{new GenericStack(lava, 750L)});

        assertEquals(ValidationDiagnostic.VALID, validate(candidate(process), pattern).diagnostic());
        var wrongAmount = TestProcessingPattern.create(pattern.getInputs(),
                new GenericStack[]{new GenericStack(lava, 749L)});
        assertRejected(validate(candidate(process), wrongAmount), ValidationDiagnostic.OUTPUT_MISMATCH);

        var tag = new CompoundTag();
        tag.putString("variant", "different");
        var taggedWater = AEFluidKey.of(Fluids.WATER, tag);
        var wrongTag = TestProcessingPattern.create(new IPatternDetails.IInput[]{
                new TestInput(new GenericStack[]{new GenericStack(taggedWater, 3_500L)}, 1L, null)
        }, new GenericStack[]{new GenericStack(lava, 750L)});
        assertRejected(validate(candidate(process), wrongTag), ValidationDiagnostic.INPUT_MISMATCH);
    }

    @Test
    void exactDeterministicPatternPromotesToInternalModel() {
        var result = validate(candidate(defaultProcess()), exactPattern());

        assertEquals(ValidationDiagnostic.VALID, result.diagnostic());
        var model = result.model().orElseThrow();
        assertEquals(id("adapter"), model.adapterId());
        assertEquals(defaultProcess().inputsPerOperation(), model.inputsPerOperation());
        assertEquals("test", model.sourceModId());
    }

    @Test
    void outputMismatchNeverProducesAModel() {
        var result = validate(candidate(defaultProcess()), pattern(1L, 2L, null, false));

        assertRejected(result, ValidationDiagnostic.OUTPUT_MISMATCH);
    }

    @Test
    void craftingAndNonProcessingPatternsCannotCrossTheCoreTrustBoundary() {
        IPatternDetails processingPattern = exactPattern();
        IPatternDetails nonProcessingPattern = new TestPattern(
                processingPattern.getInputs(), processingPattern.getOutputs());
        IPatternDetails craftingPattern = UnsafeAllocator.allocate(AECraftingPattern.class);

        assertRejected(validate(candidate(defaultProcess()), nonProcessingPattern),
                ValidationDiagnostic.UNSUPPORTED_PATTERN);
        assertRejected(validate(candidate(defaultProcess()), craftingPattern),
                ValidationDiagnostic.UNSUPPORTED_PATTERN);
    }

    @Test
    void inputAndRecipeTypeMismatchesNeverProduceAModel() {
        assertRejected(validate(candidate(defaultProcess()), pattern(2L, 1L, null, false)),
                ValidationDiagnostic.INPUT_MISMATCH);
        assertRejected(ProcessModelValidator.validate(candidate(defaultProcess()), exactPattern(),
                        ignored -> Optional.of(id("not_smelting"))),
                ValidationDiagnostic.RECIPE_TYPE_MISMATCH);
    }

    @Test
    void substitutionsAreRejectedBeforeCandidateCanExecute() {
        var result = validate(candidate(defaultProcess()), pattern(1L, 1L, null, true));

        assertRejected(result, ValidationDiagnostic.SUBSTITUTION_UNSUPPORTED);
    }

    @Test
    void nonDeterministicAndNumericallyUnsafeCandidatesFailClosed() {
        var nondeterministic = process(
                new ProcessProperties(false, true, true, true), Map.of(INPUT, 1L), Map.of(OUTPUT, 1L), Map.of());
        assertRejected(validate(candidate(nondeterministic), exactPattern()), ValidationDiagnostic.NON_DETERMINISTIC);

        var unsafe = process(
                new ProcessProperties(true, true, true, true), Map.of(INPUT, 0L), Map.of(OUTPUT, 1L), Map.of());
        assertRejected(validate(candidate(unsafe), exactPattern()), ValidationDiagnostic.NUMERICALLY_UNSAFE);

        var mutableState = process(
                new ProcessProperties(true, true, true, false), Map.of(INPUT, 1L), Map.of(OUTPUT, 1L), Map.of());
        assertRejected(validate(candidate(mutableState), exactPattern()), ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED);
    }

    @Test
    void missingRecipeAndRemainingItemMismatchFailClosed() {
        assertRejected(ProcessModelValidator.validate(candidate(defaultProcess()), exactPattern(), ignored -> Optional.empty()),
                ValidationDiagnostic.NO_RECIPE);

        var withRemaining = process(
                new ProcessProperties(true, true, true, true), Map.of(INPUT, 1L), Map.of(OUTPUT, 1L), Map.of(OUTPUT, 1L));
        assertRejected(validate(candidate(withRemaining), exactPattern()), ValidationDiagnostic.REMAINING_ITEMS_UNSUPPORTED);
    }

    @Test
    void duplicateInputKeysAreAggregatedExactly() {
        var process = process(
                new ProcessProperties(true, true, true, true),
                Map.of(INPUT, 17L), Map.of(OUTPUT, 1L), Map.of());
        var pattern = TestProcessingPattern.create(
                new IPatternDetails.IInput[]{
                        new TestInput(new GenericStack[]{new GenericStack(INPUT, 2L)}, 3L, null),
                        new TestInput(new GenericStack[]{new GenericStack(INPUT, 11L)}, 1L, null)
                },
                new GenericStack[]{new GenericStack(OUTPUT, 1L)});

        var result = validate(candidate(process), pattern);

        assertEquals(ValidationDiagnostic.VALID, result.diagnostic());
        assertEquals(Map.of(INPUT, 17L), result.model().orElseThrow().inputsPerOperation());
    }

    @Test
    void inputProductAndDuplicateAggregationOverflowFailClosed() {
        var productOverflow = TestProcessingPattern.create(
                new IPatternDetails.IInput[]{
                        new TestInput(new GenericStack[]{new GenericStack(INPUT, Long.MAX_VALUE)}, 2L, null)
                },
                new GenericStack[]{new GenericStack(OUTPUT, 1L)});
        var aggregationOverflow = TestProcessingPattern.create(
                new IPatternDetails.IInput[]{
                        new TestInput(new GenericStack[]{new GenericStack(INPUT, Long.MAX_VALUE)}, 1L, null),
                        new TestInput(new GenericStack[]{new GenericStack(INPUT, 1L)}, 1L, null)
                },
                new GenericStack[]{new GenericStack(OUTPUT, 1L)});

        assertRejected(validate(candidate(defaultProcess()), productOverflow),
                ValidationDiagnostic.NUMERICALLY_UNSAFE);
        assertRejected(validate(candidate(defaultProcess()), aggregationOverflow),
                ValidationDiagnostic.NUMERICALLY_UNSAFE);
    }

    @Test
    void verifiedModelCannotBeConstructedThroughAPublicConstructor() {
        assertFalse(List.of(VerifiedProcessModel.class.getConstructors()).stream().findAny().isPresent());
    }

    private static ValidationResult validate(ProcessCandidate candidate, IPatternDetails pattern) {
        return ProcessModelValidator.validate(candidate, pattern,
                ignored -> Optional.of(ResourceLocation.withDefaultNamespace("smelting")));
    }

    private static ProcessCandidate candidate(ResolvedProcess process) {
        return new ProcessCandidate(id("adapter"), 0, process);
    }

    private static ResolvedProcess defaultProcess() {
        return process(new ProcessProperties(true, true, true, true), Map.of(INPUT, 1L), Map.of(OUTPUT, 1L), Map.of());
    }

    private static ResolvedProcess process(
            ProcessProperties properties,
            Map<AEKey, Long> inputs,
            Map<AEKey, Long> outputs,
            Map<AEKey, Long> remaining) {
        return new ResolvedProcess(
                id("recipe"), ResourceLocation.withDefaultNamespace("smelting"),
                ProcessShapeId.of("applied_causality", "item_to_item"), id("family"), null,
                inputs, outputs, remaining, properties);
    }

    private static IPatternDetails exactPattern() {
        return pattern(1L, 1L, null, false);
    }

    private static IPatternDetails pattern(long inputAmount, long outputAmount, AEKey remaining, boolean substitution) {
        GenericStack[] possibleInputs = substitution
                ? new GenericStack[]{new GenericStack(INPUT, inputAmount), new GenericStack(OUTPUT, 1L)}
                : new GenericStack[]{new GenericStack(INPUT, inputAmount)};
        return TestProcessingPattern.create(
                new IPatternDetails.IInput[]{new TestInput(possibleInputs, 1L, remaining)},
                new GenericStack[]{new GenericStack(OUTPUT, outputAmount)});
    }

    private static void assertRejected(ValidationResult result, ValidationDiagnostic diagnostic) {
        assertEquals(diagnostic, result.diagnostic());
        assertTrue(result.model().isEmpty());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }

    private record TestPattern(IPatternDetails.IInput[] inputs, GenericStack[] outputs) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return null;
        }

        @Override
        public IInput[] getInputs() {
            return inputs;
        }

        @Override
        public GenericStack[] getOutputs() {
            return outputs;
        }
    }

    private record TestInput(GenericStack[] possibleInputs, long multiplier, AEKey remaining) implements IPatternDetails.IInput {
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
            return remaining;
        }
    }

    private static final class TestKey extends AEKey {
        private final ResourceLocation id;

        private TestKey(String path) {
            this.id = ResourceLocation.fromNamespaceAndPath("test", path);
        }

        @Override
        public AEKeyType getType() {
            return null;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag() {
            return new CompoundTag();
        }

        @Override
        public Object getPrimaryKey() {
            return id;
        }

        @Override
        public ResourceLocation getId() {
            return id;
        }

        @Override
        public void writeToPacket(FriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.empty();
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, net.minecraft.world.level.Level level, BlockPos pos) {
        }
    }
}
