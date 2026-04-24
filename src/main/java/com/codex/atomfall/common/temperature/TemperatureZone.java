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
    private final BlockPos center;
    private final double coreRadius;
    private final double maxRadius;
    private final double peakTemperatureC;
    private final double hotZoneTemperatureC;
    private final double waterFlashRadius;
    private final int lifeTicks;

    private int ageTicks;
    private double currentRadius;

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
        double cooling = Math.exp(-this.ageTicks / (double) this.lifeTicks * 2.1D);
        double sigma = Math.max(1.0D, this.currentRadius * 0.42D);
        double gaussian = Math.exp(-(distance * distance) / (2.0D * sigma * sigma));
        double base = TemperatureMaterialRules.AMBIENT_C + (this.peakTemperatureC - TemperatureMaterialRules.AMBIENT_C) * gaussian * cooling;
        double shield = shielding(level, position);
        return TemperatureMaterialRules.AMBIENT_C + (base - TemperatureMaterialRules.AMBIENT_C) * shield;
    }

    private void processEnvironment(ServerLevel level) {
        int attempts = Math.min(BlastPhysicsConstants.thermalBlockBudget(), 40 + Mth.floor((float) (this.currentRadius * 0.35D)));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos overlayPos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < attempts; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double radius = Math.sqrt(level.random.nextDouble()) * this.currentRadius;
            int x = Mth.floor(this.center.getX() + Math.cos(angle) * radius);
            int z = Mth.floor(this.center.getZ() + Math.sin(angle) * radius);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            pos.set(x, y, z);

            double absoluteTemperature = sampleAbsoluteTemperature(level, Vec3.atCenterOf(pos));
            if (absoluteTemperature <= TemperatureMaterialRules.AMBIENT_C + 10.0D) {
                continue;
            }

            applySurfaceHeat(level, pos, absoluteTemperature);
            for (int dy = 1; dy <= 8; dy++) {
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
                    for (int canopyY = y + dy + 1; canopyY <= y + dy + 6 && canopyY < level.getMaxY(); canopyY++) {
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
            if (radius <= this.waterFlashRadius && level.random.nextFloat() < 0.75F) {
                BlockPos waterSeed = WaterEvaporationUtil.findWaterSeed(level, x, z, 20);
                if (waterSeed != null) {
                    flashBoilWater(level, waterSeed, absoluteTemperature);
                }
            }
        }
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

    private double shielding(ServerLevel level, Vec3 position) {
        return level.canSeeSky(BlockPos.containing(position.x, position.y + 1.0D, position.z)) ? 1.0D : 0.55D;
    }
}
