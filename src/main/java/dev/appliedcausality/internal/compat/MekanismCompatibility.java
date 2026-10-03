package dev.appliedcausality.internal.compat;

import net.minecraftforge.fml.loading.LoadingModList;

/** Exact-version gate for the optional Mekanism recipe adapters. */
public final class MekanismCompatibility {
    public static final String MOD_ID = "mekanism";
    public static final String SUPPORTED_VERSION = "10.4.16";

    private MekanismCompatibility() {
    }

    public static boolean isLoadedAndCompatible() {
        var loadingMods = LoadingModList.get();
        return loadingMods != null && loadingMods.getMods().stream()
                .filter(mod -> MOD_ID.equals(mod.getModId()))
                .map(mod -> mod.getVersion().toString())
                .anyMatch(MekanismCompatibility::isCompatibleVersion);
    }

    public static boolean isCompatibleVersion(String version) {
        return SUPPORTED_VERSION.equals(version);
    }
}
