package dev.appliedcausality.internal.config;

import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Evaluates only Core-verified objective metadata and currently retained grants. */
public final class ConfiguredUnlockPolicy {
    private ConfiguredUnlockPolicy() {
    }

    public static Set<ResourceLocation> grants(ConfiguredRuleSnapshot rules, List<ItemStack> installed) {
        Objects.requireNonNull(rules);
        Set<ResourceLocation> result = new HashSet<>();
        for (ItemStack stack : installed) {
            if (stack.isEmpty()) {
                continue;
            }
            for (var installer : rules.installers().values()) {
                if (matchesInstaller(installer, stack)) {
                    result.addAll(installer.grants());
                }
            }
        }
        return Set.copyOf(result);
    }

    public static boolean matchesInstaller(ConfiguredRuleSnapshot.Installer installer, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (installer.item() != null && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(installer.item())) {
            return true;
        }
        return installer.itemTag() != null && stack.is(TagKey.create(
                net.minecraft.core.registries.Registries.ITEM, installer.itemTag()));
    }

    public static boolean allows(ConfiguredRuleSnapshot rules, VerifiedProcessModel model,
            Set<ResourceLocation> installedGrants) {
        Objects.requireNonNull(rules);
        Objects.requireNonNull(model);
        Objects.requireNonNull(installedGrants);
        boolean matched = false;
        for (var rule : rules.unlockRules().values()) {
            if (matches(rule, model)) {
                matched = true;
                if (!installedGrants.containsAll(rule.requires())) {
                    return false;
                }
            }
        }
        return matched || rules.defaultAllow();
    }

    public static boolean matches(ConfiguredRuleSnapshot.UnlockRule rule, VerifiedProcessModel model) {
        return (rule.recipeId() == null || rule.recipeId().equals(model.recipeId()))
                && (rule.recipeType() == null || rule.recipeType().equals(model.recipeTypeId()))
                && (rule.sourceMod() == null || rule.sourceMod().equals(model.sourceModId()))
                && (rule.processShape() == null || rule.processShape().equals(model.processShape().id()))
                && (rule.processFamily() == null || rule.processFamily().equals(model.processFamily()))
                && (rule.machineProfile() == null || rule.machineProfile().equals(model.machineProfile()));
    }
}
