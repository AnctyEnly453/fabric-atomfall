package com.codex.atomfall.common.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Centralizes low-side-effect block writes used by blast and thermal systems.
 * These writes notify clients but intentionally suppress block drops and most
 * neighbor cascades that can spawn thousands of item entities during a blast.
 */
public final class BlastWorldMutations {
    public static final int NO_DROP_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

    private BlastWorldMutations() {
    }

    public static void clearBlock(LevelAccessor level, BlockPos pos) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), NO_DROP_FLAGS);
    }

    public static void setBlock(LevelAccessor level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, NO_DROP_FLAGS);
    }
}
