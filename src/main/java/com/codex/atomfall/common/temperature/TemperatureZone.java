package com.codex.atomfall.common.temperature;

import com.codex.atomfall.common.world.BlastMaterialRules;
import com.codex.atomfall.common.world.BlastPhysicsConstants;
import com.codex.atomfall.common.world.BlastWorldMutations;
import com.codex.atomfall.common.world.WaterEvaporationUtil;
import com.codex.atomfall.registry.ModBlocks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class TemperatureZone {
    private static final double MIN_ENVIRONMENT_RING_WIDTH = 16.0D;
    private static final int THERMAL_FOOTPRINT_MAX_RADIUS = 4;

    private final BlockPos center;
    private final double coreRadius;
    private final double maxRadius;
    private final double peakTemperatureC;
    private final double hotZoneTemperatureC;
    private final double waterFlashRadius;
    private final int lifeTicks;

    private int ageTicks;
    private double currentRadius;
    private double environmentSampleRadius;
    private double environmentRingStart;
    private double environmentRingEnd;
    private int environmentChunkStartZ;
    private int environmentChunkEndX;
    private int environmentChunkEndZ;
    private int environmentChunkCursorX;
    private int environmentChunkCursorZ;
    private boolean environmentRingActive;

    private static final class EnvironmentStats {
        int budget;

        EnvironmentStats(int budget) {
            this.budget = budget;
        }
    }

    public static final Codec<TemperatureZone> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BlockPos.CODEC.fieldOf("center").forGetter(TemperatureZone::center),
                    Codec.DOUBLE.fieldOf("core_radius").forGetter(z -> z.coreRadius),
                    Codec.DOUBLE.fieldOf("max_radius").forGetter(z -> z.maxRadius),
                    Codec.DOUBLE.fieldOf("peak_temperature").forGetter(z -> z.peakTemperatureC),
                    Codec.DOUBLE.fieldOf("hot_zone_temperature").forGetter(z -> z.hotZoneTemperatureC),
                    Codec.DOUBLE.fieldOf("water_flash_radius").forGetter(z -> z.waterFlashRadius),
                    Codec.INT.fieldOf("life_ticks").forGetter(z -> z.lifeTicks),
                    Codec.INT.fieldOf("age_ticks").forGetter(z -> z.ageTicks)
            ).apply(instance, TemperatureZone::new)
    );

    public TemperatureZone(BlockPos center, double coreRadius, double maxRadius, double peakTemperatureC, double hotZoneTemperatureC, double waterFlashRadius, int lifeTicks) {
        this(center, coreRadius, maxRadius, peakTemperatureC, hotZoneTemperatureC, waterFlashRadius, lifeTicks, 0);
    }

    private TemperatureZone(BlockPos center, double coreRadius, double maxRadius, double peakTemperatureC, double hotZoneTemperatureC, double waterFlashRadius, int lifeTicks, int ageTicks) {
        this.center = center.immutable();
        this.coreRadius = Math.max(1.0D, coreRadius);
        this.maxRadius = Math.max(this.coreRadius, maxRadius);
        this.peakTemperatureC = Math.max(TemperatureMaterialRules.AMBIENT_C, peakTemperatureC);
        this.hotZoneTemperatureC = Math.max(TemperatureMaterialRules.AMBIENT_C, hotZoneTemperatureC);
        this.waterFlashRadius = Math.max(0.0D, waterFlashRadius);
        this.lifeTicks = Math.max(20, lifeTicks);
        this.ageTicks = ageTicks;
        double progress = Mth.clamp(this.ageTicks / (double) Math.max(1, this.lifeTicks / 3), 0.0D, 1.0D);
        this.currentRadius = Mth.lerp(progress, this.coreRadius, this.maxRadius);
    }

    public BlockPos center() {
        return this.center;
    }

    public double radius() {
        return this.currentRadius;
    }

    public boolean tick(ServerLevel level) {
        this.ageTicks++;
        double progress = Mth.clamp(this.ageTicks / (double) Math.max(1, this.lifeTicks / 3), 0.0D, 1.0D);
        this.currentRadius = Mth.lerp(progress, this.coreRadius, this.maxRadius);

        if (this.ageTicks % BlastPhysicsConstants.thermalEnvironmentIntervalTicks() == 0) {
            processEnvironment(level);
        }
        if (this.ageTicks % 10 == 0) {
            emitAmbientEffects(level);
        }

        return this.ageTicks >= this.lifeTicks;
    }

    public double sampleAbsoluteTemperature(ServerLevel level, Vec3 position) {
        double distance = position.distanceTo(Vec3.atCenterOf(this.center));
        if (distance > this.currentRadius) {
            return TemperatureMaterialRules.AMBIENT_C;
        }
        double ageProgress = Mth.clamp(this.ageTicks / (double) this.lifeTicks, 0.0D, 1.0D);
        double cooling = 0.18D + 0.82D * Math.exp(-ageProgress * 2.1D);
        double sigma = Math.max(1.0D, this.currentRadius * 0.42D);
        double gaussian = Math.exp(-(distance * distance) / (2.0D * sigma * sigma));
        double flash = TemperatureMaterialRules.AMBIENT_C + (this.peakTemperatureC - TemperatureMaterialRules.AMBIENT_C) * gaussian * cooling;
        double hotRadius = Math.max(this.coreRadius, this.currentRadius * 0.46D);
        double hotFalloff = distance <= this.coreRadius
                ? 1.0D
                : 1.0D - smoothstep((distance - this.coreRadius) / Math.max(1.0D, hotRadius - this.coreRadius));
        double hot = TemperatureMaterialRules.AMBIENT_C
                + (this.hotZoneTemperatureC - TemperatureMaterialRules.AMBIENT_C) * hotFalloff * (0.42D + cooling * 0.58D);
        double base = Math.max(flash, hot);
        double shield = shielding(level, position);
        return TemperatureMaterialRules.AMBIENT_C + (base - TemperatureMaterialRules.AMBIENT_C) * shield;
    }

    private void processEnvironment(ServerLevel level) {
        EnvironmentStats stats = new EnvironmentStats(Math.max(1, BlastPhysicsConstants.thermalBlockBudget()));
        while (stats.budget > 0) {
            if (!this.environmentRingActive && !startEnvironmentRing()) {
                break;
            }
            int budgetBefore = stats.budget;
            processEnvironmentRing(level, stats);
            if (stats.budget == budgetBefore && !this.environmentRingActive) {
                break;
            }
        }
    }

    private boolean startEnvironmentRing() {
        if (this.currentRadius <= 1.0D) {
            return false;
        }
        if (this.environmentSampleRadius >= this.currentRadius - 1.0D) {
            this.environmentSampleRadius = 0.0D;
        }

        this.environmentRingStart = Math.max(0.0D, this.environmentSampleRadius);
        int step = thermalColumnStep(this.environmentRingStart);
        double ringWidth = Math.max(MIN_ENVIRONMENT_RING_WIDTH, step * 5.0D);
        this.environmentRingEnd = Math.min(this.currentRadius, this.environmentRingStart + ringWidth);
        if (this.environmentRingEnd <= this.environmentRingStart + 0.25D) {
            return false;
        }

        int pad = 16 + step;
        this.environmentChunkCursorX = Mth.floor((this.center.getX() - this.environmentRingEnd - pad) / 16.0D);
        this.environmentChunkEndX = Mth.floor((this.center.getX() + this.environmentRingEnd + pad) / 16.0D);
        this.environmentChunkStartZ = Mth.floor((this.center.getZ() - this.environmentRingEnd - pad) / 16.0D);
        this.environmentChunkEndZ = Mth.floor((this.center.getZ() + this.environmentRingEnd + pad) / 16.0D);
        this.environmentChunkCursorZ = this.environmentChunkStartZ;
        this.environmentRingActive = true;
        return true;
    }

    private void processEnvironmentRing(ServerLevel level, EnvironmentStats stats) {
        while (stats.budget > 0 && this.environmentChunkCursorX <= this.environmentChunkEndX) {
            int chunkX = this.environmentChunkCursorX;
            int chunkZ = this.environmentChunkCursorZ;
            this.environmentChunkCursorZ++;
            if (this.environmentChunkCursorZ > this.environmentChunkEndZ) {
                this.environmentChunkCursorZ = this.environmentChunkStartZ;
                this.environmentChunkCursorX++;
            }

            if (!chunkIntersectsEnvironmentRing(chunkX, chunkZ)) {
                continue;
            }
            if (!level.hasChunk(chunkX, chunkZ)) {
                continue;
            }
            processEnvironmentChunk(level, stats, chunkX, chunkZ);
        }

        if (this.environmentChunkCursorX > this.environmentChunkEndX) {
            this.environmentSampleRadius = this.environmentRingEnd;
            this.environmentRingActive = false;
        }
    }

    private boolean chunkIntersectsEnvironmentRing(int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        double closestDx = closestDistance1D(this.center.getX(), minX, maxX);
        double closestDz = closestDistance1D(this.center.getZ(), minZ, maxZ);
        double closestSq = closestDx * closestDx + closestDz * closestDz;
        double outer = this.environmentRingEnd + 8.0D;
        if (closestSq > outer * outer) {
            return false;
        }
        if (this.environmentRingStart <= 1.0D) {
            return true;
        }
        double farthestDx = farthestDistance1D(this.center.getX(), minX, maxX);
        double farthestDz = farthestDistance1D(this.center.getZ(), minZ, maxZ);
        double inner = Math.max(0.0D, this.environmentRingStart - 8.0D);
        return farthestDx * farthestDx + farthestDz * farthestDz >= inner * inner;
    }

    private void processEnvironmentChunk(ServerLevel level, EnvironmentStats stats, int chunkX, int chunkZ) {
        int step = thermalColumnStep((this.environmentRingStart + this.environmentRingEnd) * 0.5D);
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        int phaseX = environmentGridPhase(chunkX, chunkZ, step, 113);
        int phaseZ = environmentGridPhase(chunkX, chunkZ, step, 197);
        int startX = alignToGrid(minX, step, this.center.getX() + phaseX);
        int startZ = alignToGrid(minZ, step, this.center.getZ() + phaseZ);

        for (int x = startX; x <= maxX && stats.budget > 0; x += step) {
            for (int z = startZ; z <= maxZ && stats.budget > 0; z += step) {
                int sampleX = jitterEnvironmentSample(x, z, step, minX, maxX, 271);
                int sampleZ = jitterEnvironmentSample(z, x, step, minZ, maxZ, 337);
                double dx = sampleX + 0.5D - this.center.getX();
                double dz = sampleZ + 0.5D - this.center.getZ();
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < this.environmentRingStart || distance > this.environmentRingEnd) {
                    continue;
                }
                stats.budget--;
                applyThermalFootprint(level, sampleX, sampleZ, distance);
            }
        }
    }

    private void applyThermalFootprint(ServerLevel level, int x, int z, double distance) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos origin = new BlockPos(x, y, z);
        double absoluteTemperature = sampleAbsoluteTemperature(level, Vec3.atCenterOf(origin));
        if (absoluteTemperature <= TemperatureMaterialRules.AMBIENT_C + 10.0D) {
            return;
        }

        int brush = thermalFootprintRadius(absoluteTemperature, distance);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos overlayPos = new BlockPos.MutableBlockPos();
        for (int ox = -brush; ox <= brush; ox++) {
            for (int oz = -brush; oz <= brush; oz++) {
                double offsetDistance = Math.sqrt(ox * (double) ox + oz * (double) oz);
                if (offsetDistance > brush + 0.35D) {
                    continue;
                }
                int nx = x + ox;
                int nz = z + oz;
                if (!level.hasChunk(nx >> 4, nz >> 4)) {
                    continue;
                }
                double noise = environmentNoise(nx, nz, 421);
                double falloff = 1.0D - Mth.clamp(offsetDistance / Math.max(1.0D, brush + 0.35D), 0.0D, 1.0D);
                double localTemperature = absoluteTemperature * Mth.clamp(0.72D + falloff * 0.24D + noise * 0.10D, 0.68D, 1.08D);
                int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz) - 1;
                applyHeatColumn(level, pos, overlayPos, nx, ny, nz, localTemperature, distance + offsetDistance);
            }
        }
    }

    private void applyHeatColumn(ServerLevel level, BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos overlayPos,
                                 int x, int y, int z, double absoluteTemperature, double distance) {
        pos.set(x, y, z);
        applySurfaceHeat(level, pos, absoluteTemperature);
        for (int dy = 1; dy <= 10; dy++) {
            overlayPos.set(x, y + dy, z);
            BlockState overlay = level.getBlockState(overlayPos);
            if (overlay.isAir()) {
                continue;
            }
            if (TemperatureMaterialRules.isLitterOrDebris(overlay)
                    || TemperatureMaterialRules.isSnow(overlay)
                    || TemperatureMaterialRules.isIce(overlay)
                    || TemperatureMaterialRules.isFlammable(overlay)) {
                applySurfaceHeat(level, overlayPos, absoluteTemperature);
            }
            if (overlay.is(net.minecraft.tags.BlockTags.LOGS)) {
                for (int canopyY = y + dy + 1; canopyY <= y + dy + 7 && canopyY < level.getMaxY(); canopyY++) {
                    overlayPos.set(x, canopyY, z);
                    BlockState canopy = level.getBlockState(overlayPos);
                    if (canopy.isAir()) {
                        continue;
                    }
                    if (canopy.is(net.minecraft.tags.BlockTags.LEAVES) || canopy.is(net.minecraft.tags.BlockTags.LOGS)) {
                        applySurfaceHeat(level, overlayPos, absoluteTemperature);
                    } else {
                        break;
                    }
                }
            }
        }
        if (distance <= this.waterFlashRadius && environmentNoise(x, z, 557) > 0.18D) {
            BlockPos waterSeed = WaterEvaporationUtil.findWaterSeed(level, x, z, 20);
            if (waterSeed != null) {
                flashBoilWater(level, waterSeed, absoluteTemperature);
            }
        }
    }

    private int thermalFootprintRadius(double absoluteTemperature, double distance) {
        int radius = 1;
        if (absoluteTemperature >= TemperatureMaterialRules.SOIL_SCORCH_POINT_C) {
            radius = 2;
        }
        if (absoluteTemperature >= TemperatureMaterialRules.WOOD_IGNITION_POINT_C) {
            radius = 3;
        }
        if (absoluteTemperature >= TemperatureMaterialRules.GLASS_SOFTENING_POINT_C || distance <= this.coreRadius) {
            radius = 4;
        }
        return Math.min(radius, THERMAL_FOOTPRINT_MAX_RADIUS);
    }

    private void applySurfaceHeat(ServerLevel level, BlockPos.MutableBlockPos pos, double absoluteTemperature) {
        BlockState state = level.getBlockState(pos);
        double materialTemp = absoluteTemperature * TemperatureMaterialRules.heatCoupling(state);

        if (state.getFluidState().isSource() && materialTemp >= TemperatureMaterialRules.WATER_BOILING_POINT_C) {
            flashBoilWater(level, pos.immutable(), materialTemp);
            return;
        }

        if (TemperatureMaterialRules.isSnow(state) && materialTemp >= TemperatureMaterialRules.SNOW_SUBLIMATION_POINT_C) {
            vaporizeFrozenCluster(level, pos.immutable(), materialTemp, true);
            return;
        }

        if (TemperatureMaterialRules.isIce(state) && materialTemp >= TemperatureMaterialRules.ICE_SUBLIMATION_POINT_C) {
            vaporizeFrozenCluster(level, pos.immutable(), materialTemp, false);
            return;
        }

        if (TemperatureMaterialRules.isLitterOrDebris(state) && materialTemp >= TemperatureMaterialRules.WATER_BOILING_POINT_C) {
            BlastWorldMutations.clearBlock(level, pos);
            level.sendParticles(
                    ParticleTypes.ASH,
                    pos.getX() + 0.5D,
                    pos.getY() + 0.35D,
                    pos.getZ() + 0.5D,
                    2,
                    0.12D,
                    0.06D,
                    0.12D,
                    0.0D
            );
            return;
        }

        if (TemperatureMaterialRules.isVitrifiable(state) && materialTemp >= TemperatureMaterialRules.SAND_GLASSING_POINT_C) {
            BlastWorldMutations.setBlock(level, pos, ModBlocks.FUSED_GLASS.get().defaultBlockState());
            return;
        }

        if (TemperatureMaterialRules.isSoil(state) && materialTemp >= TemperatureMaterialRules.SOIL_SCORCH_POINT_C) {
            BlastWorldMutations.setBlock(level, pos, ModBlocks.SCORCHED_EARTH.get().defaultBlockState());
            return;
        }

        if (TemperatureMaterialRules.isSoftGlass(state) && materialTemp >= TemperatureMaterialRules.GLASS_SOFTENING_POINT_C) {
            BlastWorldMutations.clearBlock(level, pos);
            return;
        }

        if (TemperatureMaterialRules.isFlammable(state) && materialTemp >= TemperatureMaterialRules.WOOD_IGNITION_POINT_C) {
            if (materialTemp >= 820.0D && (state.is(net.minecraft.tags.BlockTags.LEAVES) || state.is(net.minecraft.tags.BlockTags.LOGS))) {
                BlastWorldMutations.clearBlock(level, pos);
                level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 0.6D, pos.getZ() + 0.5D, 3, 0.12D, 0.12D, 0.12D, 0.01D);
                return;
            }
            BlockPos above = pos.above();
            if (level.isEmptyBlock(above)) {
                BlastWorldMutations.setBlock(level, above, BaseFireBlock.getState(level, above));
            }
            return;
        }

        if (materialTemp >= 1100.0D && state.is(Blocks.STONE)) {
            BlastWorldMutations.setBlock(level, pos, thermallyAlterRock(pos));
        }
    }

    private void flashBoilWater(ServerLevel level, BlockPos origin, double absoluteTemperature) {
        int depth = 4 + Mth.floor((float) Mth.clamp((absoluteTemperature - 100.0D) / 260.0D, 0.0D, 8.0D));
        int budget = 12 + Mth.floor((float) Mth.clamp((absoluteTemperature - 100.0D) / 18.0D, 0.0D, BlastPhysicsConstants.waterBfsBudget()));
        int removed = WaterEvaporationUtil.evaporateAround(level, origin, depth, budget, true);
        if (removed > 0) {
            level.playSound(null, origin, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.45F, 0.9F);
        }
    }

    private void vaporizeFrozenCluster(ServerLevel level, BlockPos origin, double materialTemp, boolean snowLike) {
        int depth = snowLike ? 2 : 3;
        int budget = snowLike
                ? 4 + Mth.floor((float) Mth.clamp((materialTemp - 100.0D) / 120.0D, 0.0D, 18.0D))
                : 3 + Mth.floor((float) Mth.clamp((materialTemp - 100.0D) / 180.0D, 0.0D, 12.0D));

        int removed = BlockPos.breadthFirstTraversal(
                origin,
                depth,
                budget,
                (current, queue) -> {
                    for (Direction direction : Direction.values()) {
                        queue.accept(current.relative(direction));
                    }
                },
                current -> {
                    BlockState state = level.getBlockState(current);
                    if (TemperatureMaterialRules.isSnow(state) || TemperatureMaterialRules.isIce(state)) {
                        BlastWorldMutations.clearBlock(level, current);
                        return BlockPos.TraversalNodeStatus.ACCEPT;
                    }
                    return current.equals(origin) ? BlockPos.TraversalNodeStatus.ACCEPT : BlockPos.TraversalNodeStatus.SKIP;
                }
        );

        int vaporized = Math.max(1, removed - 1);
        level.sendParticles(
                ParticleTypes.CLOUD,
                origin.getX() + 0.5D,
                origin.getY() + 0.5D,
                origin.getZ() + 0.5D,
                Math.min(12, 2 + vaporized),
                0.22D,
                0.12D,
                0.22D,
                0.024D
        );
        level.sendParticles(
                ParticleTypes.POOF,
                origin.getX() + 0.5D,
                origin.getY() + 0.55D,
                origin.getZ() + 0.5D,
                Math.min(8, 1 + vaporized / 2),
                0.16D,
                0.10D,
                0.16D,
                0.012D
        );
        level.playSound(null, origin, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.38F, snowLike ? 1.1F : 0.85F);
    }

    private BlockState thermallyAlterRock(BlockPos pos) {
        long hash = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()) * 214013L + 2531011L;
        int roll = Math.floorMod((int) (hash ^ (hash >>> 32)), 100);
        if (roll < 14) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (roll < 56) {
            return Blocks.BLACKSTONE.defaultBlockState();
        }
        return Blocks.BASALT.defaultBlockState();
    }

    private void emitAmbientEffects(ServerLevel level) {
        double ring = Math.max(4.0D, this.currentRadius * 0.35D);
        for (int i = 0; i < 8; i++) {
            double angle = i * (Math.PI * 2.0D / 8.0D) + level.random.nextDouble() * 0.18D;
            double x = this.center.getX() + 0.5D + Math.cos(angle) * ring;
            double z = this.center.getZ() + 0.5D + Math.sin(angle) * ring;
            double y = this.center.getY() + 0.6D + level.random.nextDouble() * Math.max(1.0D, this.currentRadius * 0.08D);
            level.sendParticles(ParticleTypes.ASH, x, y, z, 1, 0.05D, 0.04D, 0.05D, 0.0D);
            if (this.ageTicks < this.lifeTicks / 2) {
                level.sendParticles(ParticleTypes.SMOKE, x, y + 0.08D, z, 1, 0.03D, 0.03D, 0.03D, 0.0D);
            }
        }
    }

    private int thermalColumnStep(double radius) {
        if (radius <= this.coreRadius) {
            return 3;
        }
        if (radius <= this.currentRadius * 0.45D) {
            return 5;
        }
        if (radius <= this.currentRadius * 0.75D) {
            return 7;
        }
        return 9;
    }

    private static int environmentGridPhase(int chunkX, int chunkZ, int step, int salt) {
        if (step <= 1) {
            return 0;
        }
        long hash = ((long) chunkX * 341873128712L) ^ ((long) chunkZ * 132897987541L) ^ salt;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return Math.floorMod((int) (hash ^ (hash >>> 32)), step);
    }

    private static int alignToGrid(int min, int step, int origin) {
        if (step <= 1) {
            return min;
        }
        int offset = Math.floorMod(min - origin, step);
        return offset == 0 ? min : min + (step - offset);
    }

    private static int jitterEnvironmentSample(int value, int other, int step, int min, int max, int salt) {
        if (step <= 2) {
            return Mth.clamp(value, min, max);
        }
        int span = Math.max(1, step / 2);
        long hash = BlockPos.asLong(value, salt, other);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        int jitter = Math.floorMod((int) (hash ^ (hash >>> 32)), span * 2 + 1) - span;
        return Mth.clamp(value + jitter, min, max);
    }

    private static double environmentNoise(int x, int z, int salt) {
        long hash = BlockPos.asLong(x, salt, z);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return Math.floorMod((int) (hash ^ (hash >>> 32)), 10000) / 9999.0D;
    }

    private static double closestDistance1D(double point, int min, int max) {
        if (point < min) {
            return min - point;
        }
        if (point > max) {
            return point - max;
        }
        return 0.0D;
    }

    private static double farthestDistance1D(double point, int min, int max) {
        return Math.max(Math.abs(point - min), Math.abs(point - max));
    }

    private static double smoothstep(double value) {
        double t = Mth.clamp(value, 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private double shielding(ServerLevel level, Vec3 position) {
        return level.canSeeSky(BlockPos.containing(position.x, position.y + 1.0D, position.z)) ? 1.0D : 0.55D;
    }
}
