package com.codex.atomfall.registry;

import com.codex.atomfall.AtomfallMod;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;

import java.util.function.Supplier;

public final class ModCreativeTabs {
    public static final Supplier<CreativeModeTab> ATOMFALL_TAB = register("atomfall", FabricItemGroup.builder()
            .title(Component.translatable("itemGroup.atomfall"))
            .icon(() -> ModBlocks.ATOMIC_BOMB_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(ModBlocks.ATOMIC_BOMB_ITEM.get());
                output.accept(ModItems.DETONATOR.get());
                output.accept(ModItems.TEMPERATURE_GAUGE.get());
                output.accept(ModItems.GEIGER_COUNTER.get());
                output.accept(ModBlocks.SCORCHED_EARTH_ITEM.get());
                output.accept(ModBlocks.FUSED_GLASS_ITEM.get());
            })
            .build());

    private ModCreativeTabs() {
    }

    public static void init() {
    }

    private static Supplier<CreativeModeTab> register(String path, CreativeModeTab tab) {
        Identifier id = Identifier.fromNamespaceAndPath(AtomfallMod.MODID, path);
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id, tab);
        return () -> tab;
    }
}
