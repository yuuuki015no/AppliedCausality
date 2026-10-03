package dev.appliedcausality.gametest;

import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.internal.config.ConfiguredRuleService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Confirms the live server publishes its own initial rule generation. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase23RuleLifecycleGameTests {
    private Phase23RuleLifecycleGameTests() {
    }

    @GameTest(template = "empty")
    public static void startupPublishesServerOwnedSnapshot(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        helper.assertTrue(ConfiguredRuleService.generation(server) == 1,
                "initial rules must be published for this server");
        helper.assertTrue(ConfiguredRuleService.snapshot(server).generation()
                        == ConfiguredRuleService.generation(server),
                "the active snapshot must belong to this server");
        helper.succeed();
    }
}
