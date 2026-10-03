package dev.appliedcausality.gametest;

import java.util.Set;

import appeng.api.networking.IGridService;
import appeng.core.definitions.AEBlockEntities;
import appeng.core.definitions.AEBlocks;
import appeng.me.Grid;
import appeng.me.GridNode;
import appeng.blockentity.crafting.CraftingBlockEntity;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.internal.budget.BulkDispatchReservation;
import dev.appliedcausality.internal.budget.IBulkDispatchBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Phase 18 coverage using AE2's real GridServices-created service instances. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Phase18DispatchBudgetGameTests {
    private Phase18DispatchBudgetGameTests() {
    }

    @GameTest(template = "empty")
    public static void realGridServicesArePerGridAndResetAtServerTickStart(GameTestHelper helper) {
        Grid firstGrid = newGrid(helper, BlockPos.ZERO);
        Grid secondGrid = newGrid(helper, new BlockPos(2, 0, 0));
        IBulkDispatchBudget firstBudget = firstGrid.getService(IBulkDispatchBudget.class);
        IBulkDispatchBudget firstBudgetAgain = firstGrid.getService(IBulkDispatchBudget.class);
        IBulkDispatchBudget secondBudget = secondGrid.getService(IBulkDispatchBudget.class);

        require(firstBudget == firstBudgetAgain, "one grid did not share its dispatch service");
        require(firstBudget != secondBudget, "independent grids shared a dispatch service");
        for (int dispatch = 0; dispatch < IBulkDispatchBudget.MAX_BULK_DISPATCHES_PER_TICK; dispatch++) {
            BulkDispatchReservation reservation = firstBudget.tryReserve();
            require(reservation != null, "real grid budget ended early");
            reservation.commit();
        }
        require(!firstBudget.hasCapacity(), "real grid budget did not enforce its limit");
        require(secondBudget.hasCapacity(), "one grid consumed another grid's budget");

        firstGrid.onServerStartTick();
        require(firstBudget.hasCapacity(), "real Grid onServerStartTick did not reset the budget");
        boolean unregisteredServiceThrew = false;
        try {
            firstGrid.getService(UnregisteredGridService.class);
        } catch (IllegalArgumentException expected) {
            unregisteredServiceThrew = true;
        }
        require(unregisteredServiceThrew, "real Grid did not reject an unregistered service");
        helper.succeed();
    }

    private static Grid newGrid(GameTestHelper helper, BlockPos relativePosition) {
        BlockPos position = helper.absolutePos(relativePosition);
        var owner = new CraftingBlockEntity(
                AEBlockEntities.CRAFTING_STORAGE,
                position,
                AEBlocks.CRAFTING_STORAGE_256K.block().defaultBlockState());
        owner.setLevel(helper.getLevel());
        var node = new GridNode(helper.getLevel(), owner,
                (CraftingBlockEntity ignoredOwner, appeng.api.networking.IGridNode ignoredNode) -> { }, Set.of());
        return Grid.create(node);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    private interface UnregisteredGridService extends IGridService {
    }
}
