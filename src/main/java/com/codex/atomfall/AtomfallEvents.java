package com.codex.atomfall;

import com.codex.atomfall.common.radiation.RadiationSavedData;
import com.codex.atomfall.common.radiation.RadiationSystem;
import com.codex.atomfall.common.temperature.TemperatureSavedData;
import com.codex.atomfall.common.temperature.TemperatureSystem;
import com.codex.atomfall.common.world.NuclearBlast;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class AtomfallEvents {
    private static int entityTickCounter;
    private static final int ENTITY_SCAN_INTERVAL_TICKS = 10;

    private AtomfallEvents() {
    }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(AtomfallEvents::onEndWorldTick);
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            RadiationSystem.copyPersistentData(oldPlayer, newPlayer);
            TemperatureSystem.copyPersistentData(oldPlayer, newPlayer);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            RadiationSystem.removePersistentData(oldPlayer);
            TemperatureSystem.removePersistentData(oldPlayer);
        });
        ServerPlayerEvents.LEAVE.register(player -> {
            RadiationSystem.removePersistentData(player);
            TemperatureSystem.removePersistentData(player);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof LivingEntity living && !(living instanceof Player)) {
                RadiationSystem.removePersistentData(living);
                TemperatureSystem.removePersistentData(living);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof Player)) {
                RadiationSystem.removePersistentData(entity);
                TemperatureSystem.removePersistentData(entity);
            }
        });
    }

    private static void onEndWorldTick(ServerLevel level) {
        NuclearBlast.tick(level);
        RadiationSavedData radiation = RadiationSavedData.get(level);
        TemperatureSavedData temperature = TemperatureSavedData.get(level);
        radiation.serverTick(level);
        temperature.serverTick(level);

        for (ServerPlayer player : level.players()) {
            RadiationSystem.tickPlayer(player);
            TemperatureSystem.tickPlayer(player);
        }

        if (++entityTickCounter % ENTITY_SCAN_INTERVAL_TICKS == 0) {
            tickNearbyEntities(level, radiation, temperature);
        }
    }

    private static void tickNearbyEntities(ServerLevel level, RadiationSavedData radiation, TemperatureSavedData temperature) {
        if (!radiation.hasZones() && !temperature.hasZones()) {
            return;
        }

        Set<UUID> processed = new HashSet<>();
        for (var zone : radiation.getZones()) {
            Vec3 center = Vec3.atCenterOf(zone.center());
            double radius = zone.radius();
            AABB zoneBox = new AABB(center.x - radius, center.y - radius, center.z - radius, center.x + radius, center.y + radius, center.z + radius);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, zoneBox, e -> !(e instanceof Player))) {
                if (processed.add(entity.getUUID())) {
                    RadiationSystem.tickLivingEntity(entity);
                    TemperatureSystem.tickLivingEntity(entity);
                }
            }
        }
        for (var zone : temperature.getZones()) {
            Vec3 center = Vec3.atCenterOf(zone.center());
            double radius = zone.radius();
            AABB zoneBox = new AABB(center.x - radius, center.y - 16.0D, center.z - radius, center.x + radius, center.y + 48.0D, center.z + radius);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, zoneBox, e -> !(e instanceof Player))) {
                if (processed.add(entity.getUUID())) {
                    RadiationSystem.tickLivingEntity(entity);
                    TemperatureSystem.tickLivingEntity(entity);
                }
            }
        }
    }
}
