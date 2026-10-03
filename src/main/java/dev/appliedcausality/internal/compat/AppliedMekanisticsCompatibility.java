package dev.appliedcausality.internal.compat;

import net.minecraftforge.fml.loading.LoadingModList;

/** Exact optional Chemical-key addon gate for bundled Mekanism recipes. */
public final class AppliedMekanisticsCompatibility {
    public static final String MOD_ID = "appmek";
    public static final String SUPPORTED_VERSION = "1.4.3";

    private AppliedMekanisticsCompatibility() {
    }

    public static boolean isLoadedAndCompatible() {
        var loadingMods = LoadingModList.get();
        return loadingMods != null && loadingMods.getMods().stream()
                .filter(mod -> MOD_ID.equals(mod.getModId()))
                .map(mod -> mod.getVersion().toString())
                .anyMatch(SUPPORTED_VERSION::equals);
    }
}
