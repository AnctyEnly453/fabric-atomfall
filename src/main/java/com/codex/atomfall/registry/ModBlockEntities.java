package com.codex.atomfall.registry;

import com.codex.atomfall.AtomfallMod;
import com.codex.atomfall.common.block.entity.AtomicBombBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.function.Supplier;

public final class ModBlockEntities {
    public static final Supplier<BlockEntityType<AtomicBombBlockEntity>> ATOMIC_BOMB = register("atomic_bomb",
            FabricBlockEntityTypeBuilder.create(AtomicBombBlockEntity::new, ModBlocks.ATOMIC_BOMB.get()).build());

    private ModBlockEntities() {
    }

    public static void init() {
    }

    private static <T extends net.minecraft.world.level.block.entity.BlockEntity> Supplier<BlockEntityType<T>> register(String path, BlockEntityType<T> type) {
        Identifier id = Identifier.fromNamespaceAndPath(AtomfallMod.MODID, path);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, type);
        return () -> type;
    }
}
