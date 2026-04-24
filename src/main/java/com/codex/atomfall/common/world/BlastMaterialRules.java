package com.codex.atomfall.common.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A lightweight, explainable structural model: each block gets a nominal blast
 * resistance in psi, then exposure and shielding modify the effective
 * threshold. This is intentionally more physical than "hardness only", but
 * still cheap enough for kilometer-scale shells.
 */
public final class BlastMaterialRules {
    private static final it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap<BlockState> SURFACE_FAILURE_CACHE = new it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap<>();

    private BlastMaterialRules() {
    }

    public static double blastResistancePsi(BlockState state) {
        if (state.isAir()) {
            return Double.POSITIVE_INFINITY;
        }
        if (isLeafLike(state) || isFragileVegetation(state)) {
            return 0.10D;
        }
        if (state.is(BlockTags.IMPERMEABLE) || state.is(Blocks.GLASS_PANE) || state.is(Blocks.TINTED_GLASS)
                || state.getBlock() instanceof net.minecraft.world.level.block.StainedGlassPaneBlock) {
            return BlastPhysicsConstants.PSI_BREAK_GLASS;
        }
        if (state.is(Blocks.COBWEB) || state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) {
            return 0.3D;
        }
        if (state.is(BlockTags.DOORS) || state.is(BlockTags.FENCES) || state.is(BlockTags.TRAPDOORS)
                || state.is(Blocks.LADDER) || state.is(Blocks.TORCH) || state.is(Blocks.LANTERN)) {
            return 1.0D;
        }
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS) || state.is(Blocks.BARREL)
                || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.BOOKSHELF) || state.is(Blocks.CHEST)) {
            return 2.1D;
        }
        if (state.is(Blocks.BRICKS) || state.is(Blocks.BRICK_STAIRS) || state.is(Blocks.BRICK_WALL)
                || state.is(Blocks.BRICK_SLAB) || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.STONE_BRICK_STAIRS)
                || state.is(Blocks.STONE_BRICK_WALL) || state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLESTONE_WALL)) {
            return 4.8D;
        }
        if (state.is(Blocks.STONE) || state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE)
                || state.is(Blocks.ANDESITE) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)) {
            return 7.8D;
        }
        if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.REINFORCED_DEEPSLATE)
                || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)) {
            return 15.0D;
        }
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MUD) || state.is(Blocks.CLAY)) {
            return 1.2D;
        }
        return 4.0D;
    }

    public static boolean isLooseSurface(BlockState state) {
        return state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.PODZOL) || state.is(Blocks.CLAY) || state.is(Blocks.MUD)
                || state.is(Blocks.FARMLAND);
    }

    public static boolean isWaterSensitive(BlockState state) {
        return state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT) || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS) || state.is(Blocks.WATER);
    }

    public static boolean isRoofLike(BlockState state) {
        return state.is(BlockTags.SLABS) || state.is(BlockTags.STAIRS) || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(BlockTags.WOODEN_DOORS) || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE);
    }

    public static boolean isVitrifiable(BlockState state) {
        return state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE);
    }

    public static boolean isScorchable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.FARMLAND);
    }

    public static boolean isFlammable(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOOL) || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.BOOKSHELF)
                || isFragileVegetation(state);
    }

    public static boolean isLeafLike(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN) || state.is(Blocks.VINE)
                || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.RED_MUSHROOM)
                || state.getBlock() instanceof net.minecraft.world.level.block.BushBlock;
    }

    public static boolean isWoodFraming(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_TRAPDOORS) || state.is(Blocks.BARREL)
                || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.BOOKSHELF);
    }

    public static boolean isVegetationOrLightStructure(BlockState state) {
        return isLeafLike(state) || state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOOL) || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.BOOKSHELF)
                || state.is(BlockTags.DOORS) || state.is(BlockTags.FENCES) || state.is(BlockTags.TRAPDOORS);
    }

    public static boolean isFragileVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.VINE) || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.RED_MUSHROOM)
                || state.is(Blocks.DANDELION) || state.is(Blocks.POPPY)
                || state.getBlock() instanceof net.minecraft.world.level.block.BushBlock;
    }

    public static boolean isMasonryStructure(BlockState state) {
        return state.is(Blocks.BRICKS) || state.is(Blocks.BRICK_STAIRS) || state.is(Blocks.BRICK_WALL)
                || state.is(Blocks.BRICK_SLAB) || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.STONE_BRICK_STAIRS) || state.is(Blocks.STONE_BRICK_WALL)
                || state.is(Blocks.STONE_BRICK_SLAB) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLESTONE_WALL);
    }

    public static boolean isStructureShell(BlockState state) {
        return isRoofLike(state) || isVegetationOrLightStructure(state) || isMasonryStructure(state)
                || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(Blocks.TINTED_GLASS);
    }

    /**
     * Surface spall / scour threshold is intentionally lower than structural
     * collapse threshold. Exposed rock can lose a top layer under reflected
     * surface overpressure without the whole mountain behaving like a building.
     */
    public static double surfaceFailurePsi(ServerLevel level, BlockPos pos, BlockState state) {
        if (SURFACE_FAILURE_CACHE.containsKey(state)) {
            return SURFACE_FAILURE_CACHE.getDouble(state);
        }
        float destroySpeed = state.getDestroySpeed(level, pos);
        if (destroySpeed < 0.0F) {
            SURFACE_FAILURE_CACHE.put(state, Double.POSITIVE_INFINITY);
            return Double.POSITIVE_INFINITY;
        }

        double hardnessFactor = 0.68D + Math.min(0.88D, Math.max(0.0F, destroySpeed) * 0.085D);
        double threshold = blastResistancePsi(state) * 0.48D * hardnessFactor;

        if (isLooseSurface(state)) {
            threshold *= 0.52D;
        } else if (isScorchable(state) || isVitrifiable(state)) {
            threshold *= 0.66D;
        } else if (isRockLike(state)) {
            threshold *= 0.82D;
        } else if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.OBSIDIAN) || state.is(Blocks.REINFORCED_DEEPSLATE)) {
            threshold *= 1.12D;
        }

        double result = Math.max(0.25D, threshold);
        SURFACE_FAILURE_CACHE.put(state, result);
        return result;
    }

    public static boolean isRockLike(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE)
                || state.is(Blocks.ANDESITE) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.COBBLESTONE) || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.TUFF) || state.is(Blocks.CALCITE) || state.is(Blocks.BASALT);
    }
}
