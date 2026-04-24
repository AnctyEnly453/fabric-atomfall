package com.codex.atomfall.registry;

import com.codex.atomfall.AtomfallMod;
import com.codex.atomfall.common.entity.NuclearCloudEntity;
import com.codex.atomfall.common.entity.ShockwaveRingEntity;
import com.codex.atomfall.common.entity.ThermalPulseRingEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.function.Supplier;

public final class ModEntities {
    public static final Supplier<EntityType<NuclearCloudEntity>> NUCLEAR_CLOUD = register("nuclear_cloud",
            EntityType.Builder.<NuclearCloudEntity>of(NuclearCloudEntity::new, MobCategory.MISC)
                    .fireImmune()
                    .sized(2.0F, 2.0F)
                    .clientTrackingRange(256)
                    .updateInterval(1));

    public static final Supplier<EntityType<ShockwaveRingEntity>> SHOCKWAVE_RING = register("shockwave_ring",
            EntityType.Builder.<ShockwaveRingEntity>of(ShockwaveRingEntity::new, MobCategory.MISC)
                    .fireImmune()
                    .sized(1.0F, 0.2F)
                    .clientTrackingRange(256)
                    .updateInterval(1));

    public static final Supplier<EntityType<ThermalPulseRingEntity>> THERMAL_PULSE_RING = register("thermal_pulse_ring",
            EntityType.Builder.<ThermalPulseRingEntity>of(ThermalPulseRingEntity::new, MobCategory.MISC)
                    .fireImmune()
                    .sized(1.0F, 0.2F)
                    .clientTrackingRange(256)
                    .updateInterval(1));

    private ModEntities() {
    }

    public static void init() {
    }

    private static <T extends Entity> Supplier<EntityType<T>> register(String path, EntityType.Builder<T> builder) {
        Identifier id = Identifier.fromNamespaceAndPath(AtomfallMod.MODID, path);
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
        EntityType<T> entityType = Registry.register(BuiltInRegistries.ENTITY_TYPE, id, builder.build(key));
        return () -> entityType;
    }
}
