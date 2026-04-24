package com.codex.atomfall.common.temperature;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class TemperatureMaterialRules {
    private TemperatureMaterialRules() {
    }

    public static final double AMBIENT_C = 20.0D;
    public static final double WATER_BOILING_POINT_C = 100.0D;
    public static final double WOOD_IGNITION_POINT_C = 260.0D;
    public static final double SOIL_SCORCH_POINT_C = 180.0D;
    public static final double GLASS_SOFTENING_POINT_C = 700.0D;
    public static final double SAND_GLASSING_POINT_C = 1400.0D;
    public static final double SNOW_SUBLIMATION_POINT_C = 100.0D;
    public static final double ICE_SUBLIMATION_POINT_C = 100.0D;

    public static boolean isFlammable(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.WOOL) || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.BOOKSHELF)
                || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.POPPY)
                || state.is(Blocks.DANDELION);
    }

    public static boolean isSoil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.FARMLAND);
    }

    public static boolean isVitrifiable(BlockState state) {
        return state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE);
    }

    public static boolean isSoftGlass(BlockState state) {
        return state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(Blocks.TINTED_GLASS);
    }

    public static boolean isSnow(BlockState state) {
        return state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW);
    }

    public static boolean isIce(BlockState state) {
        return state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE) || state.is(Blocks.FROSTED_ICE);
    }

    public static boolean isLitterOrDebris(BlockState state) {
        return state.is(Blocks.LEAF_LITTER)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.MOSS_CARPET)
                || state.is(BlockTags.FLOWERS);
    }

    public static double heatCoupling(BlockState state) {
        if (state.isAir()) {
            return 0.0D;
        }
        if (state.getFluidState().isSource()) {
            return 1.2D;
        }
        if (isSnow(state) || isIce(state)) {
            return 1.35D;
        }
        if (isLitterOrDebris(state)) {
            return 1.15D;
        }
        if (isFlammable(state)) {
            return 1.0D;
        }
        if (isSoil(state)) {
            return 0.85D;
        }
        if (isVitrifiable(state)) {
            return 1.05D;
        }
        if (state.is(Blocks.STONE) || state.is(Blocks.GRANITE) || state.is(Blocks.DEEPSLATE)) {
            return 0.65D;
        }
        return 0.75D;
    }
}
