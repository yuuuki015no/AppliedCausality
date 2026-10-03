package dev.appliedcausality.gametest;

import dev.appliedcausality.AppliedCausality;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.fml.ModList;

/** Proves the real Forge common-setup path starts without optional Mekanism classes. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase15NoMekanismStartupGameTests {
    private Phase15NoMekanismStartupGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void startsWithoutMekanismApiOrImplementation(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded(AppliedCausality.MOD_ID), "Applied Causality did not load");
        helper.assertTrue(!ModList.get().isLoaded("mekanism"), "Mekanism unexpectedly loaded");
        try {
            Class.forName("mekanism.api.recipes.ItemStackToItemStackRecipe", false,
                    Phase15NoMekanismStartupGameTests.class.getClassLoader());
            helper.fail("Mekanism API class unexpectedly exists on the runtime classpath");
        } catch (ClassNotFoundException expected) {
            helper.succeed();
        }
    }
}
