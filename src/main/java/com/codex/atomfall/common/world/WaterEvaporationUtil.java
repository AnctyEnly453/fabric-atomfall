package com.codex.atomfall.common.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;

/**
 * Sponge-style water removal adapted for thermal flash boiling.
 *
 * The traversal logic mirrors the vanilla sponge approach:
 * - breadth-first traversal
 * - only expand through actual water-connected cells
 * - handle liquid blocks, water plants, and waterlogged blocks
 * - optionally clear waterlogged blocks as a pragmatic extension for blast use
 */
public final class WaterEvaporationUtil {
    private static final Direction[] ALL_DIRECTIONS = Direction.values();

    private WaterEvaporationUtil() {
    }

    public static int evaporateAround(ServerLevel level, BlockPos seed, int maxDepth, int maxCount, boolean spawnEffects) {
        int removed = BlockPos.breadthFirstTraversal(
                seed,
                maxDepth,
                maxCount,
                (current, queue) -> {
                    for (Direction direction : ALL_DIRECTIONS) {
                        queue.accept(current.relative(direction));
                    }
                },
                current -> removeWaterNode(seed, level, current)
        );

        int evaporated = removed;
        if (evaporated > 0 && spawnEffects) {
            level.sendParticles(
                    ParticleTypes.CLOUD,
                    seed.getX() + 0.5D,
                    seed.getY() + 0.55D,
                    seed.getZ() + 0.5D,
                    Math.min(18, 3 + evaporated / 10),
                    0.28D,
                    0.16D,
                    0.28D,
                    0.02D
            );
            level.playSound(null, seed, SoundEvents.SPONGE_ABSORB, SoundSource.BLOCKS, 0.55F, 0.9F);
        }
        return evaporated;
    }

    public static BlockPos findWaterSeed(ServerLevel level, int x, int z, int verticalDepth) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int bottom = Math.max(level.getMinY() + 1, top - Math.max(1, verticalDepth));
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isWaterConnected(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    private static BlockPos.TraversalNodeStatus removeWaterNode(BlockPos seed, Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluidState = level.getFluidState(pos);
        if (!fluidState.is(FluidTags.WATER)) {
            return BlockPos.TraversalNodeStatus.SKIP;
        }

        if (state.getBlock() instanceof LiquidBlock) {
            BlastWorldMutations.clearBlock(level, pos);
            return BlockPos.TraversalNodeStatus.ACCEPT;
        }

        if (BlastMaterialRules.isWaterSensitive(state)) {
            BlastWorldMutations.clearBlock(level, pos);
            return BlockPos.TraversalNodeStatus.ACCEPT;
        }

        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && Boolean.TRUE.equals(state.getValue(BlockStateProperties.WATERLOGGED))) {
            BlastWorldMutations.setBlock(level, pos, state.setValue(BlockStateProperties.WATERLOGGED, false));
            return BlockPos.TraversalNodeStatus.ACCEPT;
        }

        return BlockPos.TraversalNodeStatus.SKIP;
    }

    private static boolean isWaterConnected(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.WATER)
                || (state.hasProperty(BlockStateProperties.WATERLOGGED) && Boolean.TRUE.equals(state.getValue(BlockStateProperties.WATERLOGGED)));
    }
}
