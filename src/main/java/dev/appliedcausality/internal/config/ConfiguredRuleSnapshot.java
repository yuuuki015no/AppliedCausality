package dev.appliedcausality.internal.config;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Server-owned, immutable rule data. Execution still requires Core validation. */
public record ConfiguredRuleSnapshot(
        long generation,
        Map<ResourceLocation, RecipeTypeProfile> recipeTypes,
        Map<ResourceLocation, Installer> installers,
        Set<ResourceLocation> grants,
        Map<ResourceLocation, UnlockRule> unlockRules,
        boolean defaultAllow,
        int installerSlots) {
    public ConfiguredRuleSnapshot(long generation,
            Map<ResourceLocation, RecipeTypeProfile> recipeTypes,
            Map<ResourceLocation, Installer> installers,
            Set<ResourceLocation> grants,
            Map<ResourceLocation, UnlockRule> unlockRules,
            boolean defaultAllow) {
        this(generation, recipeTypes, installers, grants, unlockRules, defaultAllow, 4);
    }
    public ConfiguredRuleSnapshot(long generation,
            Map<ResourceLocation, RecipeTypeProfile> recipeTypes,
            Map<ResourceLocation, Installer> installers,
            Set<ResourceLocation> grants,
            Map<ResourceLocation, UnlockRule> unlockRules) {
        this(generation, recipeTypes, installers, grants, unlockRules, true, 4);
    }
    public ConfiguredRuleSnapshot {
        if (generation < 0) {
            throw new IllegalArgumentException("Negative rule generation");
        }
        if (installerSlots < 1 || installerSlots > 4) {
            throw new IllegalArgumentException("Active Installer slot count out of range");
        }
        recipeTypes = Collections.unmodifiableMap(new LinkedHashMap<>(recipeTypes));
        installers = Collections.unmodifiableMap(new LinkedHashMap<>(installers));
        grants = Collections.unmodifiableSet(new LinkedHashSet<>(grants));
        unlockRules = Collections.unmodifiableMap(new LinkedHashMap<>(unlockRules));
    }

    public static ConfiguredRuleSnapshot empty() {
        return new ConfiguredRuleSnapshot(0, Map.of(), Map.of(), Set.of(), Map.of());
    }

    public ConfiguredRuleSnapshot withGeneration(long next) {
        return new ConfiguredRuleSnapshot(next, recipeTypes, installers, grants, unlockRules,
                defaultAllow, installerSlots);
    }

    public ConfiguredRuleSnapshot withDefaultAllow(boolean allow) {
        return new ConfiguredRuleSnapshot(generation, recipeTypes, installers, grants, unlockRules,
                allow, installerSlots);
    }

    public ConfiguredRuleSnapshot withInstallerSlots(int slots) {
        return new ConfiguredRuleSnapshot(generation, recipeTypes, installers, grants, unlockRules,
                defaultAllow, slots);
    }

    public record RecipeTypeProfile(
            ResourceLocation id,
            ResourceLocation recipeType,
            ResourceLocation shape,
            ResourceLocation family,
            ResourceLocation machineProfile,
            Set<ResourceLocation> excludedRecipes) {
        public RecipeTypeProfile {
            Objects.requireNonNull(id);
            Objects.requireNonNull(recipeType);
            Objects.requireNonNull(shape);
            Objects.requireNonNull(family);
            excludedRecipes = Collections.unmodifiableSet(new LinkedHashSet<>(excludedRecipes));
        }
    }

    public record Installer(
            ResourceLocation id,
            ResourceLocation item,
            ResourceLocation itemTag,
            Set<ResourceLocation> grants) {
        public Installer(ResourceLocation id, ResourceLocation item, Set<ResourceLocation> grants) {
            this(id, item, null, grants);
        }
        public Installer {
            Objects.requireNonNull(id);
            if ((item == null) == (itemTag == null)) {
                throw new IllegalArgumentException("Installer needs exactly one item or item tag");
            }
            grants = Collections.unmodifiableSet(new LinkedHashSet<>(grants));
        }
    }

    public record UnlockRule(
            ResourceLocation id,
            ResourceLocation recipeId,
            ResourceLocation recipeType,
            String sourceMod,
            ResourceLocation processShape,
            ResourceLocation processFamily,
            ResourceLocation machineProfile,
            List<ResourceLocation> requires) {
        public UnlockRule {
            Objects.requireNonNull(id);
            requires = List.copyOf(requires);
        }
    }
}
