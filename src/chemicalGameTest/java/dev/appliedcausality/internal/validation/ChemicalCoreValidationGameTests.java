package dev.appliedcausality.internal.validation;

import java.util.Map;
import java.util.Optional;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.api.process.ResolvedProcess;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.slurry.SlurryStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Uses a test recipe lookup to exercise Core's exact Chemical Pattern boundary. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalCoreValidationGameTests {
    private static final long AMOUNT = (long) Integer.MAX_VALUE + 1L;
    private static final ResourceLocation RECIPE_TYPE = id("chemical_core_test_type");

    private ChemicalCoreValidationGameTests() {
    }

    @GameTest(template = "empty")
    public static void allChemicalFormsUseExactPatternLongAmounts(GameTestHelper helper) {
        var gas = MekanismAPI.gasRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_GAS).findFirst().orElseThrow();
        var infusion = MekanismAPI.infuseTypeRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_INFUSE_TYPE).findFirst().orElseThrow();
        var pigment = MekanismAPI.pigmentRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_PIGMENT).findFirst().orElseThrow();
        var slurry = MekanismAPI.slurryRegistry().getValues().stream()
                .filter(value -> value != MekanismAPI.EMPTY_SLURRY).findFirst().orElseThrow();
        AEKey[] keys = {
                MekanismKey.of(new GasStack(gas, 7L)),
                MekanismKey.of(new InfusionStack(infusion, 7L)),
                MekanismKey.of(new PigmentStack(pigment, 7L)),
                MekanismKey.of(new SlurryStack(slurry, 7L))
        };

        for (int form = 0; form < keys.length; form++) {
            var input = keys[form];
            var output = keys[(form + 1) % keys.length];
            require(input != null && output != null, "Chemical key was empty");
            var process = new ResolvedProcess(
                    id("chemical_core_test_recipe"), RECIPE_TYPE,
                    ProcessShapeId.of(AppliedCausality.MOD_ID, "chemical_to_chemical"),
                    id("chemical_core_test_family"), null,
                    Map.of(input, AMOUNT), Map.of(output, AMOUNT), Map.of(),
                    new ProcessProperties(true, true, true, true));
            var candidate = new ProcessCandidate(id("chemical_core_test_adapter"), 0, process);
            var matching = pattern(helper, input, AMOUNT, output, AMOUNT);
            var result = ProcessModelValidator.validate(candidate, matching,
                    ignored -> Optional.of(RECIPE_TYPE));
            require(result.diagnostic() == ValidationDiagnostic.VALID
                            && result.model().orElseThrow().inputsPerOperation().get(input) == AMOUNT
                            && result.model().orElseThrow().outputsPerOperation().get(output) == AMOUNT,
                    "Core did not preserve exact Chemical long amounts for form " + form);

            var wrongInput = ProcessModelValidator.validate(candidate,
                    pattern(helper, input, AMOUNT - 1L, output, AMOUNT),
                    ignored -> Optional.of(RECIPE_TYPE));
            require(wrongInput.diagnostic() == ValidationDiagnostic.INPUT_MISMATCH
                            && wrongInput.model().isEmpty(),
                    "Core accepted wrong Chemical input for form " + form);
            var wrongOutput = ProcessModelValidator.validate(candidate,
                    pattern(helper, input, AMOUNT, output, AMOUNT - 1L),
                    ignored -> Optional.of(RECIPE_TYPE));
            require(wrongOutput.diagnostic() == ValidationDiagnostic.OUTPUT_MISMATCH
                            && wrongOutput.model().isEmpty(),
                    "Core accepted wrong Chemical output for form " + form);
        }
        helper.succeed();
    }

    private static appeng.api.crafting.IPatternDetails pattern(GameTestHelper helper, AEKey input,
            long inputAmount, AEKey output, long outputAmount) {
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, inputAmount)},
                new GenericStack[]{new GenericStack(output, outputAmount)}), helper.getLevel());
        require(pattern != null, "Chemical Core test Pattern did not decode");
        return pattern;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AppliedCausality.MOD_ID, path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
