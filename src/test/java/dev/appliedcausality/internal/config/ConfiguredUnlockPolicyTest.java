package dev.appliedcausality.internal.config;

import appeng.api.stacks.AEKey;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfiguredUnlockPolicyTest {
    private static final ResourceLocation GRANT = id("grant");

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void retainedItemAndBlockItemGrantOnlyWhilePresentAndOnlyUnderCurrentDefinition() {
        var installer = new ConfiguredRuleSnapshot.Installer(id("installer"),
                ResourceLocation.withDefaultNamespace("stone"), Set.of(GRANT));
        var rules = new ConfiguredRuleSnapshot(1, Map.of(), Map.of(installer.id(), installer),
                Set.of(GRANT), Map.of(), false);
        assertTrue(ConfiguredUnlockPolicy.grants(rules, List.of(new ItemStack(Items.STONE))).contains(GRANT));
        assertTrue(ConfiguredUnlockPolicy.grants(rules, List.of(ItemStack.EMPTY)).isEmpty());
        assertTrue(ConfiguredUnlockPolicy.grants(rules.withGeneration(2), List.of(new ItemStack(Items.DIRT)))
                .isEmpty());
        var removedDefinition = new ConfiguredRuleSnapshot(2, Map.of(), Map.of(), Set.of(GRANT), Map.of(), false);
        assertTrue(ConfiguredUnlockPolicy.grants(removedDefinition, List.of(new ItemStack(Items.STONE))).isEmpty());
    }

    @Test
    void everySupportedMatcherIsRestrictedToItsVerifiedMetadata() {
        var model = model();
        List<ConfiguredRuleSnapshot.UnlockRule> rules = List.of(
                rule(model.recipeId(), null, null, null, null, null),
                rule(null, model.recipeTypeId(), null, null, null, null),
                rule(null, null, model.sourceModId(), null, null, null),
                rule(null, null, null, model.processShape().id(), null, null),
                rule(null, null, null, null, model.processFamily(), null),
                rule(null, null, null, null, null, model.machineProfile()));
        for (var rule : rules) {
            var snapshot = new ConfiguredRuleSnapshot(1, Map.of(), Map.of(), Set.of(GRANT),
                    Map.of(rule.id(), rule), false);
            assertFalse(ConfiguredUnlockPolicy.allows(snapshot, model, Set.of()));
            assertTrue(ConfiguredUnlockPolicy.allows(snapshot, model, Set.of(GRANT)));
        }
        assertFalse(ConfiguredUnlockPolicy.matches(rule(id("different"), null, null, null, null, null), model));
        assertFalse(ConfiguredUnlockPolicy.matches(rule(null, id("different"), null, null, null, null), model));
        assertFalse(ConfiguredUnlockPolicy.matches(rule(null, null, "different", null, null, null), model));
        assertFalse(ConfiguredUnlockPolicy.matches(rule(null, null, null, id("different"), null, null), model));
        assertFalse(ConfiguredUnlockPolicy.matches(rule(null, null, null, null, id("different"), null), model));
        assertFalse(ConfiguredUnlockPolicy.matches(rule(null, null, null, null, null, id("different")), model));
    }

    @Test
    void unmatchedDefaultAndOverlappingRulesFailClosed() {
        var model = model();
        var exact = rule(model.recipeId(), null, null, null, null, null);
        var shape = new ConfiguredRuleSnapshot.UnlockRule(id("shape_rule"), null, null,
                null, model.processShape().id(), null, null, List.of(GRANT));
        var broad = new ConfiguredRuleSnapshot.UnlockRule(id("broad"), null, null,
                model.sourceModId(), null, null, null, List.of());
        var deny = new ConfiguredRuleSnapshot(1, Map.of(), Map.of(), Set.of(GRANT), Map.of(), false);
        assertFalse(ConfiguredUnlockPolicy.allows(deny, model, Set.of()));
        assertTrue(ConfiguredUnlockPolicy.allows(deny.withDefaultAllow(true), model, Set.of()));
        var overlap = new ConfiguredRuleSnapshot(1, Map.of(), Map.of(), Set.of(GRANT),
                Map.of(exact.id(), exact, shape.id(), shape, broad.id(), broad), false);
        assertFalse(ConfiguredUnlockPolicy.allows(overlap, model, Set.of()));
        assertTrue(ConfiguredUnlockPolicy.allows(overlap, model, Set.of(GRANT)));
    }

    @Test
    void slotCountChangeKeepsSnapshotBounded() {
        var rules = ConfiguredRuleSnapshot.empty().withInstallerSlots(2);
        assertTrue(rules.installerSlots() == 2);
        assertTrue(rules.withGeneration(1).installerSlots() == 2);
        assertThrows(IllegalArgumentException.class, () -> rules.withInstallerSlots(0));
        assertThrows(IllegalArgumentException.class, () -> rules.withInstallerSlots(5));
    }

    private static ConfiguredRuleSnapshot.UnlockRule rule(ResourceLocation recipeId,
            ResourceLocation recipeType, String sourceMod, ResourceLocation shape,
            ResourceLocation family, ResourceLocation machine) {
        return new ConfiguredRuleSnapshot.UnlockRule(id("rule"), recipeId, recipeType,
                sourceMod, shape, family, machine, List.of(GRANT));
    }

    private static VerifiedProcessModel model() {
        try {
            Constructor<VerifiedProcessModel> constructor = VerifiedProcessModel.class.getDeclaredConstructor(
                    ResourceLocation.class, ResourceLocation.class, ResourceLocation.class,
                    ProcessShapeId.class, ResourceLocation.class, ResourceLocation.class,
                    Map.class, Map.class, Map.class, ProcessProperties.class);
            constructor.setAccessible(true);
            return constructor.newInstance(id("adapter"), id("recipe"), id("type"),
                    ProcessShapeId.of("test", "shape"), id("family"), id("machine"),
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
