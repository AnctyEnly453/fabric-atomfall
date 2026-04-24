package com.codex.atomfall.common.block;

import com.codex.atomfall.common.world.BlastWorldMutations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class ScorchedEarthBlock extends Block {
    public ScorchedEarthBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextFloat() < 0.08F && level.isEmptyBlock(pos.above())) {
            BlastWorldMutations.clearBlock(level, pos);
        }
    }
}
