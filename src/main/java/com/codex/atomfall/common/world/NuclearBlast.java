package com.codex.atomfall.common.world;

import com.codex.atomfall.common.entity.NuclearCloudEntity;
import com.codex.atomfall.common.entity.ShockwaveRingEntity;
import com.codex.atomfall.common.entity.ThermalPulseRingEntity;
import com.codex.atomfall.common.radiation.RadiationSavedData;
import com.codex.atomfall.common.radiation.RadiationZone;
import com.codex.atomfall.common.temperature.TemperatureSavedData;
import com.codex.atomfall.common.temperature.TemperatureZone;
import com.codex.atomfall.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Central entry point for the single weapon supported by the mod.
 */
public final class NuclearBlast {
    private NuclearBlast() {
    }

    public static void detonate(ServerLevel level, BlockPos detonationPos, double yieldKt) {
        BlastGeometry geometry = BlastGeometry.fromYield(yieldKt);
        Vec3 center = Vec3.atCenterOf(detonationPos);

        level.playSound(null, detonationPos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 20.0F, 0.55F);
        level.playSound(null, detonationPos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 7.5F, 0.55F);
        level.sendParticles(ParticleTypes.END_ROD, center.x, center.y + 1.0D, center.z, 8, 0.15D, 0.15D, 0.15D, 0.0D);
        level.sendParticles(
                ParticleTypes.EXPLOSION_EMITTER,
                center.x,
                center.y + 1.0D,
                center.z,
                28,
                geometry.fireballRadius() * 0.08D,
                geometry.fireballRadius() * 0.05D,
                geometry.fireballRadius() * 0.08D,
                0.0D
        );

        spawnVisualEntities(level, center, geometry);
        registerPersistentFields(level, detonationPos, geometry);

        ActiveBlastSavedData.get(level).addBlast(new ActiveNuclearBlast(detonationPos.immutable(), center, geometry));
    }

    public static void tick(ServerLevel level) {
        ActiveBlastSavedData.get(level).serverTick(level);
    }

    private static void spawnVisualEntities(ServerLevel level, Vec3 center, BlastGeometry geometry) {
        ShockwaveRingEntity shockwave = new ShockwaveRingEntity(ModEntities.SHOCKWAVE_RING.get(), level);
        shockwave.setPos(center.x, center.y + 0.05D, center.z);
        shockwave.configure(geometry.yieldKt(), (float) geometry.shockSurfaceRadius());
        level.addFreshEntity(shockwave);

        ThermalPulseRingEntity thermalPulse = new ThermalPulseRingEntity(ModEntities.THERMAL_PULSE_RING.get(), level);
        thermalPulse.setPos(center.x, center.y + 0.15D, center.z);
        thermalPulse.configure((float) geometry.thermalRadius(), (float) geometry.thermalCoreRadius());
        level.addFreshEntity(thermalPulse);

        NuclearCloudEntity cloud = new NuclearCloudEntity(ModEntities.NUCLEAR_CLOUD.get(), level);
        cloud.setPos(center.x, center.y + 1.0D, center.z);
        cloud.configure((float) geometry.yieldKt(), Mth.ceil(280 + geometry.cubeRootScale() * 90.0D));
        level.addFreshEntity(cloud);
    }

    private static void registerPersistentFields(ServerLevel level, BlockPos center, BlastGeometry geometry) {
        TemperatureSavedData.get(level).addZone(new TemperatureZone(
                center,
                geometry.thermalCoreRadius() * 1.5D,
                geometry.thermalRadius() * 1.4D,
                geometry.maxTemperatureC() * 1.25D,
                geometry.hotZoneTemperatureC() * 1.3D,
                geometry.waterFlashRadius() * 1.5D,
                20 * 120
        ));

        RadiationSavedData.get(level).addZone(new RadiationZone(
                center,
                geometry.promptRadiationRadius(),
                geometry.falloutRadius(),
                1.0D + geometry.cubeRootScale() * 0.5D,
                20 * 60 * 20
        ));
    }

    public record BlastGeometry(
            double yieldKt,
            double cubeRootScale,
            double fireballRadius,
            double craterRadius,
            double craterDepth,
            double shockCoreRadius,
            double shockSevereRadius,
            double shockSurfaceRadius,
            double thermalCoreRadius,
            double vitrificationRadius,
            double scorchRadius,
            double thermalRadius,
            double waterFlashRadius,
            double promptRadiationRadius,
            double falloutRadius,
            double maxTemperatureC,
            double hotZoneTemperatureC
    ) {
        public static BlastGeometry fromYield(double yieldKt) {
            double clamped = BlastPhysicsConstants.clampYield(yieldKt);
            double scale = BlastPhysicsConstants.cubeRootScale(clamped);
            return new BlastGeometry(
                    clamped,
                    scale,
                    BlastPhysicsConstants.fireballRadius(clamped),
                    BlastPhysicsConstants.craterRadius(clamped),
                    BlastPhysicsConstants.craterDepth(clamped),
                    BlastPhysicsConstants.scaledRadius(BlastPhysicsConstants.SHOCK_CORE_RADIUS_1KT, clamped),
                    BlastPhysicsConstants.scaledRadius(BlastPhysicsConstants.SHOCK_SEVERE_RADIUS_1KT, clamped),
                    BlastPhysicsConstants.scaledRadius(BlastPhysicsConstants.SHOCK_SURFACE_RADIUS_1KT, clamped),
                    BlastPhysicsConstants.scaledRadius(BlastPhysicsConstants.THERMAL_CORE_RADIUS_1KT, clamped),
                    BlastPhysicsConstants.scaledRadius(BlastPhysicsConstants.THERMAL_VITRIFICATION_RADIUS_1KT, clamped),
                    BlastPhysicsConstants.scaledRadius(BlastPhysicsConstants.THERMAL_SCORCH_RADIUS_1KT, clamped),
                    BlastPhysicsConstants.scaledRadius(BlastPhysicsConstants.THERMAL_RADIUS_1KT, clamped),
                    BlastPhysicsConstants.scaledRadius(BlastPhysicsConstants.WATER_FLASH_RADIUS_1KT, clamped),
                    BlastPhysicsConstants.scaledRadius(BlastPhysicsConstants.PROMPT_RADIATION_RADIUS_1KT, clamped),
                    BlastPhysicsConstants.scaledRadius(BlastPhysicsConstants.FALLOUT_RADIUS_1KT, clamped),
                    BlastPhysicsConstants.FIREBALL_TEMPERATURE_C + scale * 220.0D,
                    BlastPhysicsConstants.HOT_ZONE_TEMPERATURE_C + scale * 110.0D
            );
        }

        public double peakOverpressurePsi(double distance) {
            return BlastPhysicsConstants.peakOverpressurePsi(this.yieldKt, distance);
        }

        public double shockFrontSpeed(double distance) {
            return BlastPhysicsConstants.shockFrontSpeed(this.yieldKt, distance);
        }
    }
}
