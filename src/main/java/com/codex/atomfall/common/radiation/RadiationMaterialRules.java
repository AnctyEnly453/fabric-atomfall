package com.codex.atomfall.common.radiation;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class RadiationMaterialRules {
    private RadiationMaterialRules() {
    }

    public static double transmission(BlockState state) {
        if (state.isAir()) {
            return 1.0D;
        }
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(BlockTags.LEAVES)) {
            return 0.82D;
        }
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) {
            return 0.62D;
        }
        if (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SAND) || state.is(Blocks.GRAVEL)) {
            return 0.36D;
        }
        if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.BRICKS)) {
            return 0.22D;
        }
        if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.OBSIDIAN) || state.is(Blocks.REINFORCED_DEEPSLATE)) {
            return 0.10D;
        }
        return 0.48D;
    }
}
