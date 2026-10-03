package dev.appliedcausality.gametest;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.core.definitions.AEBlocks;
import mekanism.api.math.FloatingLong;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.machine.TileEntityEnergizedSmelter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;

/** Physical-machine fixtures used only by the isolated Phase 21 run. */
final class Phase21MachinePerformanceFixtures {
    private Phase21MachinePerformanceFixtures() {
    }

    static Phase21PerformanceProbe.PreparedInvocation prepareNormalAe2Mekanism(
            GameTestHelper helper, int slot) throws Exception {
        int x = 4 + 5 * (slot % 16);
        int z = 4 + 5 * (slot / 16);
        var machinePosition = new BlockPos(x, 2, z);
        helper.setBlock(machinePosition, MekanismBlocks.ENERGIZED_SMELTER.getBlock());
        var machine = (TileEntityEnergizedSmelter) helper.getBlockEntity(machinePosition);
        machine.getEnergyContainer().setEnergy(machine.getEnergyContainer().getMaxEnergy());
        machine.ticksRequired = 1;

        Direction inputSide = findInputSide(machine);
        var providerPosition = machinePosition.relative(inputSide);

        helper.setBlock(providerPosition, AEBlocks.PATTERN_PROVIDER.block());
        var provider = (PatternProviderBlockEntity) helper.getBlockEntity(providerPosition);
        var gridFixture = new Phase11StandardAe2GameTests.Fixture(helper);
        Phase11StandardAe2GameTests.setActiveNode(
                provider, provider.getMainNode(), helper, gridFixture.grid, true);

        var raw = AEItemKey.of(Items.RAW_IRON);
        var ingot = AEItemKey.of(Items.IRON_INGOT);
        var encoded = PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[] { new GenericStack(raw, 1) },
                new GenericStack[] { new GenericStack(ingot, 1) });
        provider.getLogic().getPatternInv().setItemDirect(0, encoded);
        provider.getLogic().updatePatterns();
        var pattern = provider.getLogic().getAvailablePatterns().get(0);
        var inputs = new KeyCounter[] { new KeyCounter() };
        inputs[0].set(raw, 1);
        boolean[] pushed = { false };

        return new Phase21PerformanceProbe.PreparedInvocation(
                "normal_ae2_mekanism",
                1,
                19,
                () -> {
                    pushed[0] = provider.getLogic().pushPattern(pattern, inputs);
                    TileEntityMekanism.tickServer(
                            helper.getLevel(), machine.getBlockPos(), machine.getBlockState(), machine);
                },
                () -> {
                    long produced = machine.getInventorySlots(null).stream()
                            .filter(inventorySlot -> inventorySlot.getStack().is(Items.IRON_INGOT))
                            .mapToLong(inventorySlot -> inventorySlot.getCount())
                            .sum();
                    if (!pushed[0] || produced != 1) {
                        throw new AssertionError(
                                "normal AE2 + Mekanism did not push and complete exactly one operation: pushed="
                                        + pushed[0] + ", produced=" + produced
                                        + ", operatingTicks=" + machine.getOperatingTicks()
                                        + ", ticksRequired=" + machine.getTicksRequired()
                                        + ", slots=" + machine.getInventorySlots(null).stream()
                                                .map(inventorySlot -> inventorySlot.getStack().toString())
                                                .toList());
                    }
                    helper.getLevel().removeBlock(helper.absolutePos(providerPosition), false);
                    helper.getLevel().removeBlock(helper.absolutePos(machinePosition), false);
                });
    }

    static Phase21PerformanceProbe.PreparedInvocation prepareAstralMachine(
            GameTestHelper helper, int slot) {
        int x = 4 + 5 * (slot % 16);
        int z = 4 + 5 * (slot / 16);
        var position = new BlockPos(x, 2, z);
        var id = ResourceLocation.fromNamespaceAndPath(
                "astral_mekanism", "astral_energized_smelter");
        var block = ForgeRegistries.BLOCKS.getValue(id);
        if (!(block instanceof EntityBlock entityBlock)) {
            throw new AssertionError("missing source-verified Astral machine block " + id);
        }
        var state = block.defaultBlockState();
        var machine = (TileEntityMekanism) entityBlock.newBlockEntity(
                helper.absolutePos(position), state);
        if (machine == null) {
            throw new AssertionError("Astral smelter block did not create its physical block entity");
        }
        machine.setLevel(helper.getLevel());
        assertAstralBaseline(machine);
        int operations = 64;
        var input = machine.getInventorySlots(null).stream()
                .filter(inventorySlot -> inventorySlot.isItemValid(new ItemStack(Items.RAW_IRON)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Astral smelter input slot was not found"));
        input.setStack(new ItemStack(Items.RAW_IRON, operations));
        var energy = machine.getEnergyContainers(null).stream().findFirst()
                .orElseThrow(() -> new AssertionError("Astral smelter energy container was not found"));
        energy.setEnergy(energy.getMaxEnergy());

        return new Phase21PerformanceProbe.PreparedInvocation(
                "astral_mekanism_physical",
                operations,
                -1,
                () -> TileEntityMekanism.tickServer(
                        helper.getLevel(), machine.getBlockPos(), machine.getBlockState(), machine),
                () -> {
                    long produced = machine.getInventorySlots(null).stream()
                            .filter(inventorySlot -> inventorySlot.getStack().is(Items.IRON_INGOT))
                            .mapToLong(inventorySlot -> inventorySlot.getCount())
                            .sum();
                    if (!input.isEmpty() || produced != operations) {
                        throw new AssertionError("Astral physical tick did not execute "
                                + operations + " exact operations: input=" + input.getCount()
                                + ", output=" + produced);
                    }
                });
    }

    private static Direction findInputSide(TileEntityEnergizedSmelter machine) {
        for (Direction side : Direction.values()) {
            var capability = machine.getCapability(ForgeCapabilities.ITEM_HANDLER, side).resolve();
            if (capability.isEmpty()) {
                continue;
            }
            var handler = capability.orElseThrow();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                var remainder = handler.insertItem(slot, new ItemStack(Items.RAW_IRON), true);
                if (remainder.isEmpty()) {
                    return side;
                }
            }
        }
        throw new AssertionError("Mekanism Energized Smelter exposed no raw-iron input side");
    }

    private static void assertAstralBaseline(TileEntityMekanism machine) {
        try {
            var method = machine.getClass().getDeclaredMethod("getBaselineMaxOperations");
            method.setAccessible(true);
            if ((int) method.invoke(machine) != Integer.MAX_VALUE) {
                throw new AssertionError("Astral smelter baseline max operations changed");
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("could not verify Astral smelter operation baseline", exception);
        }
    }

}
