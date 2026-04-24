package com.codex.atomfall.registry;

import com.codex.atomfall.AtomfallMod;
import com.codex.atomfall.common.block.AtomicBombBlock;
import com.codex.atomfall.common.block.ScorchedEarthBlock;
import com.codex.atomfall.common.item.BombBlockItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.function.Function;
import java.util.function.Supplier;

public final class ModBlocks {
    public static final Supplier<Block> ATOMIC_BOMB = register("atomic_bomb", properties -> new AtomicBombBlock(properties
            .mapColor(MapColor.COLOR_BLACK)
            .strength(5.0F, 12.0F)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops()));

    public static final Supplier<Block> SCORCHED_EARTH = register("scorched_earth", properties -> new ScorchedEarthBlock(properties
            .mapColor(MapColor.COLOR_BLACK)
            .strength(0.7F)
            .sound(SoundType.GRAVEL)
            .randomTicks()));

    public static final Supplier<Block> FUSED_GLASS = register("fused_glass", properties -> new Block(properties
            .mapColor(MapColor.COLOR_LIGHT_GRAY)
            .strength(0.8F)
            .sound(SoundType.GLASS)
            .noOcclusion()));

    public static final Supplier<Item> ATOMIC_BOMB_ITEM = ModItems.register("atomic_bomb", properties -> new BombBlockItem(ATOMIC_BOMB.get(), properties.stacksTo(1)));
    public static final Supplier<Item> SCORCHED_EARTH_ITEM = ModItems.register("scorched_earth", properties -> new BlockItem(SCORCHED_EARTH.get(), properties.useBlockDescriptionPrefix()));
    public static final Supplier<Item> FUSED_GLASS_ITEM = ModItems.register("fused_glass", properties -> new BlockItem(FUSED_GLASS.get(), properties.useBlockDescriptionPrefix()));

    private ModBlocks() {
    }

    public static void init() {
    }

    private static <T extends Block> Supplier<T> register(String path, Function<BlockBehaviour.Properties, T> factory) {
        Identifier id = Identifier.fromNamespaceAndPath(AtomfallMod.MODID, path);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
        T block = factory.apply(BlockBehaviour.Properties.of().setId(key));
        Registry.register(BuiltInRegistries.BLOCK, id, block);
        return () -> block;
    }
}
