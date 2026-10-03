package dev.appliedcausality.internal.provider;

import dev.appliedcausality.internal.config.ConfiguredRuleSnapshot;
import dev.appliedcausality.internal.config.ConfiguredUnlockPolicy;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Set;

/** Applied only after Core has produced a verified model. */
public final class ProviderUnlockPolicy {
    private ProviderUnlockPolicy() {
    }

    public static boolean allows(boolean creative, ConfiguredRuleSnapshot rules,
            VerifiedProcessModel model, Set<ResourceLocation> installedGrants) {
        Objects.requireNonNull(rules);
        Objects.requireNonNull(model);
        Objects.requireNonNull(installedGrants);
        return creative || ConfiguredUnlockPolicy.allows(rules, model, installedGrants);
    }
}
