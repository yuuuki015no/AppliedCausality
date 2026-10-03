package dev.appliedcausality.internal.compat;

import net.minecraftforge.fml.loading.LoadingModList;

/** Exact-version gate for the optional AdvancedAE execution bridge. */
public final class AdvancedAeCompatibility {
    public static final String MOD_ID = "advanced_ae";
    public static final String SUPPORTED_VERSION = "1.3.5-1.20.1";

    private AdvancedAeCompatibility() {
    }

    public static boolean isLoadedAndCompatible() {
        var loadingMods = LoadingModList.get();
        return loadingMods != null && loadingMods.getMods().stream()
                .filter(mod -> MOD_ID.equals(mod.getModId()))
                .map(mod -> mod.getVersion().toString())
                .anyMatch(AdvancedAeCompatibility::isCompatibleVersion);
    }

    public static boolean isCompatibleVersion(String version) {
        return SUPPORTED_VERSION.equals(version);
    }
}
