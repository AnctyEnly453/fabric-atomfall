package com.codex.atomfall.common.radiation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class RadiationZone {
    private final BlockPos center;
    private final double promptRadius;
    private final double falloutRadius;
    private final double peakRate;
    private final int lifeTicks;

    private int ageTicks;
    private double currentRadius;

    public static final Codec<RadiationZone> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BlockPos.CODEC.fieldOf("center").forGetter(RadiationZone::center),
                    Codec.DOUBLE.fieldOf("prompt_radius").forGetter(z -> z.promptRadius),
                    Codec.DOUBLE.fieldOf("fallout_radius").forGetter(z -> z.falloutRadius),
                    Codec.DOUBLE.fieldOf("peak_rate").forGetter(z -> z.peakRate),
                    Codec.INT.fieldOf("life_ticks").forGetter(z -> z.lifeTicks),
                    Codec.INT.fieldOf("age_ticks").forGetter(z -> z.ageTicks)
            ).apply(instance, RadiationZone::new)
    );

    public RadiationZone(BlockPos center, double promptRadius, double falloutRadius, double peakRate, int lifeTicks) {
        this(center, promptRadius, falloutRadius, peakRate, lifeTicks, 0);
    }

    private RadiationZone(BlockPos center, double promptRadius, double falloutRadius, double peakRate, int lifeTicks, int ageTicks) {
        this.center = center.immutable();
        this.promptRadius = Math.max(1.0D, promptRadius);
        this.falloutRadius = Math.max(this.promptRadius, falloutRadius);
        this.peakRate = Math.max(0.05D, peakRate);
        this.lifeTicks = Math.max(20 * 60, lifeTicks);
        this.ageTicks = ageTicks;
        double growth = Mth.clamp(this.ageTicks / (double) Math.max(1, this.lifeTicks / 6), 0.0D, 1.0D);
        this.currentRadius = Mth.lerp(growth, this.promptRadius, this.falloutRadius);
    }

    public BlockPos center() {
        return this.center;
    }

    public double radius() {
        return this.currentRadius;
    }

    public boolean tick(ServerLevel level) {
        this.ageTicks++;
        double growth = Mth.clamp(this.ageTicks / (double) Math.max(1, this.lifeTicks / 6), 0.0D, 1.0D);
        this.currentRadius = Mth.lerp(growth, this.promptRadius, this.falloutRadius);

        if (this.ageTicks % 20 == 0) {
            double particleRing = Math.max(4.0D, this.currentRadius * 0.25D);
            for (int i = 0; i < 6; i++) {
                double angle = i * (Math.PI * 2.0D / 6.0D) + level.random.nextDouble() * 0.25D;
                double x = this.center.getX() + 0.5D + Math.cos(angle) * particleRing;
                double z = this.center.getZ() + 0.5D + Math.sin(angle) * particleRing;
                double y = this.center.getY() + 1.0D + level.random.nextDouble() * 2.0D;
                level.sendParticles(ParticleTypes.ASH, x, y, z, 1, 0.04D, 0.04D, 0.04D, 0.0D);
            }
        }

        return this.ageTicks >= this.lifeTicks;
    }

    public double sample(ServerLevel level, Vec3 position) {
        double distance = position.distanceTo(Vec3.atCenterOf(this.center));
        if (distance > this.currentRadius) {
            return 0.0D;
        }

        double promptProgress = Math.exp(-this.ageTicks / (20.0D * 90.0D));
        double falloutProgress = Math.exp(-this.ageTicks / (20.0D * 1200.0D));
        double promptSigma = Math.max(1.0D, this.promptRadius * 0.42D);
        double falloutSigma = Math.max(1.0D, this.currentRadius * 0.58D);

        double prompt = this.peakRate * Math.exp(-(distance * distance) / (2.0D * promptSigma * promptSigma)) * promptProgress;
        double fallout = this.peakRate * 0.32D * Math.exp(-(distance * distance) / (2.0D * falloutSigma * falloutSigma)) * falloutProgress;

        return (prompt + fallout) * shelterFactor(level, position);
    }

    private double shelterFactor(ServerLevel level, Vec3 position) {
        Vec3 origin = Vec3.atCenterOf(this.center).add(0.0D, 1.0D, 0.0D);
        Vec3 delta = position.subtract(origin);
        double distance = delta.length();
        if (distance <= 0.01D) {
            return 1.0D;
        }

        Vec3 step = delta.normalize().scale(1.25D);
        Vec3 current = origin;
        double factor = 1.0D;
        for (double walked = 0.0D; walked < distance; walked += 1.25D) {
            BlockState state = level.getBlockState(BlockPos.containing(current));
            factor *= RadiationMaterialRules.transmission(state);
            if (factor <= 0.05D) {
                return 0.05D;
            }
            current = current.add(step);
        }

        return Math.max(0.05D, factor);
    }
}
