package dev.appliedcausality.internal.provider;

import appeng.api.stacks.AEKey;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.internal.config.ConfiguredRuleSnapshot;
import dev.appliedcausality.internal.validation.ProcessRejection;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProviderAuthorDiagnosticsTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void rulesShowTrustProfileExclusionsMissingTagsAndRejectedReferences() {
        var profile = new ConfiguredRuleSnapshot.RecipeTypeProfile(id("profile"), id("smelting"),
                id("shape"), id("family"), null, Set.of(id("excluded")));
        var installer = new ConfiguredRuleSnapshot.Installer(id("installer"), null,
                id("absent_tag"), Set.of(id("grant")));
        var rules = new ConfiguredRuleSnapshot(4, Map.of(profile.recipeType(), profile),
                Map.of(installer.id(), installer), Set.of(id("grant")), Map.of(), false);

        var summary = ProviderAuthorDiagnostics.rules(rules,
                List.of("pack.toml: Unknown Recipe Type: test:missing"), ignored -> false, 1)
                .orElseThrow().lines();
        assertTrue(summary.stream().anyMatch(line -> line.contains("test:missing")));
        assertTrue(summary.stream().anyMatch(line -> line.contains("test:smelting")));
        assertTrue(summary.stream().anyMatch(line -> line.contains("test:absent_tag")));

        var details = ProviderAuthorDiagnostics.recipeType(rules, id("smelting"), 1).orElseThrow().lines();
        assertTrue(details.stream().anyMatch(line -> line.contains("test:profile")));
        assertTrue(details.stream().anyMatch(line -> line.contains("test:excluded")));
        assertTrue(ProviderAuthorDiagnostics.recipeType(rules, id("unconfigured"), 1)
                .orElseThrow().lines().stream().anyMatch(line -> line.contains("not configured")));
    }

    @Test
    void laterPagesExposeMissingTagAndEveryRecipeExclusion() {
        var profiles = new LinkedHashMap<ResourceLocation, ConfiguredRuleSnapshot.RecipeTypeProfile>();
        for (int index = 0; index < 31; index++) {
            ResourceLocation type = id("type_" + index);
            profiles.put(type, new ConfiguredRuleSnapshot.RecipeTypeProfile(id("profile_" + index), type,
                    id("shape"), id("family"), null, Set.of()));
        }
        var exclusions = new java.util.LinkedHashSet<ResourceLocation>();
        for (int index = 0; index < 80; index++) {
            exclusions.add(id("excluded_" + index));
        }
        ResourceLocation type = id("large_type");
        profiles.put(type, new ConfiguredRuleSnapshot.RecipeTypeProfile(id("large_profile"), type,
                id("shape"), id("family"), null, exclusions));
        var installer = new ConfiguredRuleSnapshot.Installer(id("installer"), null, id("missing_tag"), Set.of());
        var rules = new ConfiguredRuleSnapshot(3, profiles, Map.of(installer.id(), installer),
                Set.of(), Map.of());

        var first = ProviderAuthorDiagnostics.rules(rules, List.of(), ignored -> false, 1).orElseThrow();
        assertTrue(first.totalPages() > 1);
        assertTrue(IntStream.rangeClosed(1, first.totalPages())
                .boxed().flatMap(page -> ProviderAuthorDiagnostics.rules(rules, List.of(), ignored -> false, page)
                        .orElseThrow().lines().stream())
                .anyMatch(line -> line.contains("test:missing_tag")));
        var typeFirst = ProviderAuthorDiagnostics.recipeType(rules, type, 1).orElseThrow();
        assertTrue(typeFirst.totalPages() > 1);
        assertTrue(IntStream.rangeClosed(1, typeFirst.totalPages())
                .boxed().flatMap(page -> ProviderAuthorDiagnostics.recipeType(rules, type, page)
                        .orElseThrow().lines().stream())
                .anyMatch(line -> line.contains("test:excluded_79")));
        assertTrue(ProviderAuthorDiagnostics.recipeType(rules, type, typeFirst.totalPages() + 1).isEmpty());
    }

    @Test
    void utf8WrappingKeepsEveryByteWithinLineAndPageLimits() {
        String longDiagnostic = "長い診断".repeat(800) + "END";
        var first = ProviderAuthorDiagnostics.paginate(List.of(longDiagnostic), 1).orElseThrow();
        assertTrue(first.totalPages() > 1);
        var recovered = new StringBuilder();
        for (int page = 1; page <= first.totalPages(); page++) {
            var part = ProviderAuthorDiagnostics.paginate(List.of(longDiagnostic), page).orElseThrow();
            int pageBytes = 0;
            for (String line : part.lines()) {
                int bytes = line.getBytes(StandardCharsets.UTF_8).length;
                assertTrue(bytes <= ProviderAuthorDiagnostics.MAX_LINE_BYTES);
                pageBytes += bytes;
            }
            assertTrue(pageBytes <= ProviderAuthorDiagnostics.MAX_PAGE_BYTES);
            for (String line : part.lines().subList(1, part.lines().size())) {
                recovered.append(line.startsWith("... ") ? line.substring(4) : line);
            }
        }
        assertEquals(longDiagnostic, recovered.toString());
        assertFalse(ProviderAuthorDiagnostics.paginate(List.of(longDiagnostic), 0).isPresent());
    }

    @Test
    void rejectedSlotShowsRecipeAdapterAndConcreteReason() {
        var rejection = new ProcessRejection(id("configured_adapter"), id("tagged_output"),
                "RECIPE_OUTPUT_NBT");
        var status = new ProviderPatternStatus(0, ValidationDiagnostic.MUTABLE_STATE_UNSUPPORTED,
                null, null, rejection);
        var lines = ProviderAuthorDiagnostics.provider(true, List.of(status),
                ConfiguredRuleSnapshot.empty(), Set.of(), 1).orElseThrow().lines();
        assertTrue(lines.stream().anyMatch(line -> line.contains("test:tagged_output")
                && line.contains("test:configured_adapter") && line.contains("RECIPE_OUTPUT_NBT")));
    }

    @Test
    void longProfileIdAndManyMissingGrantsRemainReachableWithinByteBudgets() {
        ResourceLocation type = id("smelting");
        ResourceLocation longProfile = id("p".repeat(6_000) + "tail");
        var profile = new ConfiguredRuleSnapshot.RecipeTypeProfile(longProfile, type,
                id("shape"), id("family"), null, Set.of());
        var grants = new ArrayList<ResourceLocation>();
        for (int index = 0; index < 300; index++) {
            grants.add(id("grant_" + index));
        }
        var rule = new ConfiguredRuleSnapshot.UnlockRule(id("gate"), null, type,
                null, null, null, null, grants);
        var rules = new ConfiguredRuleSnapshot(1, Map.of(type, profile), Map.of(),
                Set.copyOf(grants), Map.of(rule.id(), rule), false);

        var typeFirst = ProviderAuthorDiagnostics.recipeType(rules, type, 1).orElseThrow();
        assertTrue(typeFirst.totalPages() > 1);
        assertTrue(allContent(page -> ProviderAuthorDiagnostics.recipeType(rules, type, page))
                .contains(longProfile.toString()));

        VerifiedProcessModel model = verifiedModel();
        var status = new ProviderPatternStatus(0, ValidationDiagnostic.LOCKED, model.recipeId(), model);
        var providerFirst = ProviderAuthorDiagnostics.provider(false, List.of(status), rules, Set.of(), 1)
                .orElseThrow();
        assertTrue(providerFirst.totalPages() > 1);
        assertTrue(allContent(page -> ProviderAuthorDiagnostics.provider(false, List.of(status), rules,
                Set.of(), page)).contains("test:grant_299"));
    }

    private static String allContent(java.util.function.IntFunction<
            java.util.Optional<ProviderAuthorDiagnostics.Page>> pageAt) {
        int count = pageAt.apply(1).orElseThrow().totalPages();
        var joined = new StringBuilder();
        for (int page = 1; page <= count; page++) {
            var lines = pageAt.apply(page).orElseThrow().lines();
            int bytes = 0;
            for (String line : lines) {
                int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                assertTrue(lineBytes <= ProviderAuthorDiagnostics.MAX_LINE_BYTES);
                bytes += lineBytes;
            }
            assertTrue(bytes <= ProviderAuthorDiagnostics.MAX_PAGE_BYTES);
            for (String line : lines.subList(1, lines.size())) {
                joined.append(line.startsWith("... ") ? line.substring(4) : line);
            }
        }
        return joined.toString();
    }

    private static VerifiedProcessModel verifiedModel() {
        try {
            var constructor = VerifiedProcessModel.class.getDeclaredConstructor(
                    ResourceLocation.class, ResourceLocation.class, ResourceLocation.class,
                    ProcessShapeId.class, ResourceLocation.class, ResourceLocation.class,
                    Map.class, Map.class, Map.class, ProcessProperties.class);
            constructor.setAccessible(true);
            return constructor.newInstance(id("adapter"), id("recipe"), id("smelting"),
                    ProcessShapeId.of("test", "shape"), id("family"), null,
                    Map.<AEKey, Long>of(), Map.<AEKey, Long>of(), Map.<AEKey, Long>of(),
                    new ProcessProperties(true, true, true, true));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
