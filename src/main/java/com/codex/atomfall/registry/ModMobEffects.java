package com.codex.atomfall.registry;

import com.codex.atomfall.AtomfallMod;
import com.codex.atomfall.common.effect.RadiationSicknessEffect;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;

import java.util.function.Supplier;

public final class ModMobEffects {
    public static final Supplier<MobEffect> RADIATION_SICKNESS = register("radiation_sickness", new RadiationSicknessEffect());

    private ModMobEffects() {
    }

    public static void init() {
    }

    private static <T extends MobEffect> Supplier<T> register(String path, T effect) {
        Identifier id = Identifier.fromNamespaceAndPath(AtomfallMod.MODID, path);
        Registry.register(BuiltInRegistries.MOB_EFFECT, id, effect);
        return () -> effect;
    }
}
