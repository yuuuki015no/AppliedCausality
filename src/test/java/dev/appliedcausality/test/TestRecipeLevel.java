package dev.appliedcausality.test;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * Constructor-free ServerLevel test double for APIs that only read the
 * RecipeManager. The real constructor is deliberately never invoked.
 */
public final class TestRecipeLevel extends ServerLevel {
    private RecipeManager recipeManager;
    private boolean failRecipeLookup;
    private BlockPos blockEntityPos;
    private BlockEntity blockEntity;

    private TestRecipeLevel() {
        super(null, null, null, null, Level.OVERWORLD, null, null,
                false, 0L, List.of(), false, null);
        throw new AssertionError("allocated without invoking the ServerLevel constructor");
    }

    public static TestRecipeLevel create(RecipeManager recipeManager) {
        var level = UnsafeAllocator.allocate(TestRecipeLevel.class);
        level.recipeManager = Objects.requireNonNull(recipeManager, "recipeManager");
        return level;
    }

    /** Replaces the server-authoritative manager after a simulated successful reload. */
    public void adoptRecipeManager(RecipeManager recipeManager) {
        this.recipeManager = Objects.requireNonNull(recipeManager, "recipeManager");
    }

    public void setRecipeLookupFailure(boolean fail) {
        this.failRecipeLookup = fail;
    }

    public void setBlockEntityForTest(BlockPos pos, BlockEntity blockEntity) {
        this.blockEntityPos = Objects.requireNonNull(pos, "pos").immutable();
        this.blockEntity = Objects.requireNonNull(blockEntity, "blockEntity");
    }

    @Override
    public RecipeManager getRecipeManager() {
        if (failRecipeLookup) {
            throw new IllegalStateException("Injected recipe lookup failure");
        }
        return recipeManager;
    }

    @Override
    public RegistryAccess registryAccess() {
        return RegistryAccess.EMPTY;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return pos.equals(blockEntityPos) ? blockEntity : null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean hasChunk(int chunkX, int chunkZ) {
        return false;
    }
}
