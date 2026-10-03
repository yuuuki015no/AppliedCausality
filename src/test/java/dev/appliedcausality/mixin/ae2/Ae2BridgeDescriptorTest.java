package dev.appliedcausality.mixin.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.Inject;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import dev.appliedcausality.internal.cpu.Ae2TaskView;
import net.minecraft.nbt.CompoundTag;

class Ae2BridgeDescriptorTest {
    @Test
    void stockTeardownMembersRemainAvailable() throws ReflectiveOperationException {
        var block = appeng.blockentity.crafting.CraftingBlockEntity.class;
        var cluster = appeng.me.cluster.implementations.CraftingCPUCluster.class;
        assertEquals(void.class, block.getDeclaredMethod("breakCluster").getReturnType());
        assertEquals(void.class, block.getDeclaredMethod("updateStatus", cluster).getReturnType());
        assertEquals(void.class, block.getDeclaredMethod("disconnect", boolean.class).getReturnType());
        assertEquals(cluster, block.getDeclaredField("cluster").getType());
        assertEquals(void.class, cluster.getDeclaredMethod("destroy").getReturnType());
        assertEquals(boolean.class, cluster.getDeclaredField("isDestroyed").getType());
        assertEquals(CraftingCpuLogic.class, cluster.getDeclaredField("craftingLogic").getType());
    }

    @Test
    void exactAe2JobAndCpuMembersRemainAvailable() throws ReflectiveOperationException {
        assertEquals(ExecutingCraftingJob.class,
                CraftingCpuLogic.class.getDeclaredField("job").getType());
        assertEquals(ListCraftingInventory.class,
                CraftingCpuLogic.class.getDeclaredField("inventory").getType());
        assertEquals(void.class,
                CraftingCpuLogic.class.getDeclaredMethod("finishJob", boolean.class).getReturnType());
        assertEquals(void.class, CraftingCpuLogic.class.getDeclaredMethod("cancel").getReturnType());
        assertEquals(int.class,
                CraftingCpuLogic.class.getDeclaredMethod("executeCrafting", int.class,
                        appeng.me.service.CraftingService.class,
                        appeng.api.networking.energy.IEnergyService.class,
                        net.minecraft.world.level.Level.class).getReturnType());
        assertEquals(void.class,
                CraftingCpuLogic.class.getDeclaredMethod("tickCraftingLogic",
                        appeng.api.networking.energy.IEnergyService.class,
                        appeng.me.service.CraftingService.class).getReturnType());
        assertEquals(long.class,
                CraftingCpuLogic.class.getDeclaredMethod("insert", AEKey.class, long.class, Actionable.class)
                        .getReturnType());
        assertEquals(void.class,
                CraftingCpuLogic.class.getDeclaredMethod("readFromNBT", CompoundTag.class).getReturnType());
        assertEquals(void.class,
                CraftingCpuLogic.class.getDeclaredMethod("writeToNBT", CompoundTag.class).getReturnType());
        assertEquals(void.class,
                CraftingCpuLogic.class.getDeclaredMethod("getAllItems", KeyCounter.class).getReturnType());
        assertEquals(long.class,
                CraftingCpuLogic.class.getDeclaredMethod("getStored", AEKey.class).getReturnType());
        assertEquals(void.class,
                CraftingCpuLogic.class.getDeclaredMethod("postChange", AEKey.class).getReturnType());

        assertEquals(Map.class, ExecutingCraftingJob.class.getDeclaredField("tasks").getType());
        assertEquals(ListCraftingInventory.class,
                ExecutingCraftingJob.class.getDeclaredField("waitingFor").getType());
        assertEquals(long.class,
                ExecutingCraftingJob.class.getDeclaredField("remainingAmount").getType());

        var taskProgress = Class.forName("appeng.crafting.execution.ExecutingCraftingJob$TaskProgress");
        assertEquals(long.class, taskProgress.getDeclaredField("value").getType());
        assertFalse(Modifier.isPublic(taskProgress.getModifiers()));

        assertEquals(void.class,
                ElapsedTimeTracker.class.getDeclaredMethod("decrementItems", long.class, AEKeyType.class)
                        .getReturnType());

        assertTrue(Modifier.isPublic(Ae2TaskView.class.getModifiers()));
        assertTrue(Modifier.isPublic(
                Ae2TaskView.class.getDeclaredConstructor(IPatternDetails.class, Object.class).getModifiers()));
        assertFalse(Ae2TaskView.class.getPackageName().contains(".mixin."));
    }

    @Test
    void executionHookIsExactCancellableHead() {
        var hooks = Arrays.stream(CraftingCpuLogicMixin.class.getDeclaredMethods())
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
        assertEquals("executeCrafting(ILappeng/me/service/CraftingService;Lappeng/api/networking/energy/IEnergyService;Lnet/minecraft/world/level/Level;)I",
                hook.method()[0]);
    }
}
