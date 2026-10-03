package dev.appliedcausality.block;

import appeng.block.AEBaseEntityBlock;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.menu.ProviderBindingMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Network block that advertises only Core-verified processing patterns. */
public final class SingularPatternProviderBlock extends AEBaseEntityBlock<SingularPatternProviderBlockEntity> {
    public SingularPatternProviderBlock() {
        super(metalProps().strength(5.0F).requiresCorrectToolForDrops());
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        var drops = new ArrayList<>(super.getDrops(state, builder));
        if (builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY)
                instanceof SingularPatternProviderBlockEntity provider && provider.replacesNormalBlockDrop()) {
            // addAdditionalDrops supplies the same block with persistent output.
            drops.removeIf(stack -> stack.is(asItem()));
        }
        return drops;
    }

    @Override
    public InteractionResult onActivated(
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            @Nullable ItemStack heldItem,
            BlockHitResult hit) {
        var blockEntity = getBlockEntity(level, pos);
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            ProviderBindingMenu.open(serverPlayer, blockEntity);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
