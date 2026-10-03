package dev.appliedcausality.mixin.advancedae;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.Inject;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.ListCraftingInventory;
import dev.appliedcausality.internal.compat.AdvancedAeCompatibility;
import dev.appliedcausality.internal.cpu.AdvancedAeTaskView;
import dev.appliedcausality.internal.cpu.IAdvancedAeCpuOwnerBridge;
import net.minecraft.nbt.CompoundTag;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPU;
import net.pedroksl.advanced_ae.common.cluster.AdvCraftingCPUCluster;
import net.pedroksl.advanced_ae.common.entities.AdvCraftingBlockEntity;
import net.pedroksl.advanced_ae.common.logic.AdvCraftingCPULogic;
import net.pedroksl.advanced_ae.common.logic.ElapsedTimeTracker;
import net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob;

class AdvancedAeBridgeDescriptorTest {
    @Test
    void exactAdvancedAeMembersRemainAvailable() throws ReflectiveOperationException {
        assertEquals(AdvCraftingCPU.class,
                AdvCraftingCPULogic.class.getDeclaredField("cpu").getType());
        assertEquals(ExecutingCraftingJob.class,
                AdvCraftingCPULogic.class.getDeclaredField("job").getType());
        assertEquals(ListCraftingInventory.class,
                AdvCraftingCPULogic.class.getDeclaredField("inventory").getType());
        assertEquals(void.class,
                AdvCraftingCPULogic.class.getDeclaredMethod("finishJob", boolean.class).getReturnType());
        assertEquals(void.class, AdvCraftingCPULogic.class.getDeclaredMethod("cancel").getReturnType());
        assertEquals(int.class,
                AdvCraftingCPULogic.class.getDeclaredMethod(
                        "executeCrafting",
                        int.class,
                        appeng.me.service.CraftingService.class,
                        appeng.api.networking.energy.IEnergyService.class,
                        net.minecraft.world.level.Level.class).getReturnType());
        assertEquals(void.class,
                AdvCraftingCPULogic.class.getDeclaredMethod(
                        "tickCraftingLogic",
                        appeng.api.networking.energy.IEnergyService.class,
                        appeng.me.service.CraftingService.class).getReturnType());
        assertEquals(long.class,
                AdvCraftingCPULogic.class.getDeclaredMethod(
                        "insert", AEKey.class, long.class, Actionable.class).getReturnType());
        assertEquals(void.class,
                AdvCraftingCPULogic.class.getDeclaredMethod(
                        "readFromNBT", CompoundTag.class).getReturnType());
        assertEquals(void.class,
                AdvCraftingCPULogic.class.getDeclaredMethod(
                        "writeToNBT", CompoundTag.class).getReturnType());
        assertEquals(void.class,
                AdvCraftingCPULogic.class.getDeclaredMethod(
                        "getAllItems", KeyCounter.class).getReturnType());
        assertEquals(long.class,
                AdvCraftingCPULogic.class.getDeclaredMethod(
                        "getStored", AEKey.class).getReturnType());
        assertEquals(void.class,
                AdvCraftingCPULogic.class.getDeclaredMethod(
                        "postChange", AEKey.class).getReturnType());

        assertEquals(Map.class, ExecutingCraftingJob.class.getDeclaredField("tasks").getType());
        assertEquals(ListCraftingInventory.class,
                ExecutingCraftingJob.class.getDeclaredField("waitingFor").getType());
        assertEquals(long.class,
                ExecutingCraftingJob.class.getDeclaredField("remainingAmount").getType());
        var taskProgress = Class.forName(
                "net.pedroksl.advanced_ae.common.logic.ExecutingCraftingJob$TaskProgress");
        assertEquals(long.class, taskProgress.getDeclaredField("value").getType());
        assertFalse(Modifier.isPublic(taskProgress.getModifiers()));
        assertEquals(void.class,
                ElapsedTimeTracker.class.getDeclaredMethod(
                        "decrementItems", long.class, AEKeyType.class).getReturnType());

        assertEquals(void.class, AdvCraftingCPUCluster.class.getDeclaredMethod("destroy").getReturnType());
        assertEquals(void.class, AdvCraftingBlockEntity.class.getDeclaredMethod("breakCluster").getReturnType());
        assertEquals(void.class,
                AdvCraftingBlockEntity.class.getDeclaredMethod(
                        "updateStatus", AdvCraftingCPUCluster.class).getReturnType());
        assertTrue(IAdvancedAeCpuOwnerBridge.class.isAssignableFrom(AdvCraftingCPUClusterMixin.class));
        assertEquals(java.util.List.class,
                AdvCraftingCPUClusterMixin.class.getDeclaredMethod(
                        "bulk$snapshotOwnedCpuLifecycles").getReturnType());
        assertTrue(Modifier.isPublic(AdvancedAeTaskView.class.getModifiers()));
    }

    @Test
    void executionHookIsThinExactAndNeverOverwritesAdvancedAe() {
        var hooks = Arrays.stream(AdvCraftingCPULogicMixin.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Inject.class))
                .filter(annotation -> annotation != null)
                .filter(annotation -> Arrays.stream(annotation.method())
                        .anyMatch(target -> target.startsWith("executeCrafting")))
                .toList();
        assertEquals(1, hooks.size());
        var hook = hooks.get(0);
        assertTrue(hook.cancellable());
        assertFalse(hook.remap());
        assertEquals("HEAD", hook.at()[0].value());
        assertEquals(
                "executeCrafting(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;Lnet/minecraft/world/level/Level;)I",
                hook.method()[0]);
        assertTrue(Arrays.stream(AdvCraftingCPULogicMixin.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(Overwrite.class)));
    }

    @Test
    void optionalConfigDisablesAbsentAndUnknownVersions() throws Exception {
        assertTrue(AdvancedAeCompatibility.isCompatibleVersion("1.3.5-1.20.1"));
        assertFalse(AdvancedAeCompatibility.isCompatibleVersion("1.3.4-1.20.1"));
        assertFalse(AdvancedAeCompatibility.isCompatibleVersion("1.3.6-1.20.1"));
        assertFalse(AdvancedAeCompatibility.isCompatibleVersion(null));
        assertFalse(new AdvancedAeMixinPlugin().shouldApplyMixin("missing.Target", "optional.Mixin"));

        try (var input = getClass().getClassLoader()
                .getResourceAsStream("applied_causality.advancedae.mixins.json")) {
            var config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("\"required\": false"));
            assertTrue(config.contains(AdvancedAeMixinPlugin.class.getName()));
        }
    }
}
