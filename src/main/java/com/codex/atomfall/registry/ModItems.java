package com.codex.atomfall.registry;

import com.codex.atomfall.AtomfallMod;
import com.codex.atomfall.common.item.DetonatorItem;
import com.codex.atomfall.common.item.GeigerCounterItem;
import com.codex.atomfall.common.item.TemperatureGaugeItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import java.util.function.Function;
import java.util.function.Supplier;

public final class ModItems {
    public static final Supplier<Item> DETONATOR = register("detonator", properties -> new DetonatorItem(properties.stacksTo(1)));
    public static final Supplier<Item> GEIGER_COUNTER = register("geiger_counter", properties -> new GeigerCounterItem(properties.stacksTo(1)));
    public static final Supplier<Item> TEMPERATURE_GAUGE = register("temperature_gauge", properties -> new TemperatureGaugeItem(properties.stacksTo(1)));

    private ModItems() {
    }

    public static void init() {
    }

    public static <T extends Item> Supplier<T> register(String path, Function<Item.Properties, T> factory) {
        Identifier id = Identifier.fromNamespaceAndPath(AtomfallMod.MODID, path);
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        T item = factory.apply(new Item.Properties().setId(key));
        Registry.register(BuiltInRegistries.ITEM, id, item);
        return () -> item;
    }
}
