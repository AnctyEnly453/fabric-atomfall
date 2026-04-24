package com.codex.atomfall.common.radiation;

import com.codex.atomfall.registry.ModMobEffects;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RadiationSystem {
    private static final Map<UUID, CompoundTag> DATA = new HashMap<>();
    private static final String RATE_TAG = "Rate";
    private static final String DOSE_TAG = "Dose";
    private static final String CONTAMINATION_TAG = "Contamination";
    private static final String LAST_SAMPLE_TICK_TAG = "LastSampleTick";
    private static final String LAST_SAMPLE_X_TAG = "LastSampleX";
    private static final String LAST_SAMPLE_Y_TAG = "LastSampleY";
    private static final String LAST_SAMPLE_Z_TAG = "LastSampleZ";
    private static final int PLAYER_SAMPLE_INTERVAL_TICKS = 5;
    private static final int ENTITY_SAMPLE_INTERVAL_TICKS = 20;
    private static final double PLAYER_SAMPLE_DISTANCE_SQR = 1.0D;
    private static final double ENTITY_SAMPLE_DISTANCE_SQR = 4.0D;

    private RadiationSystem() {
    }

    public static void tickPlayer(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        CompoundTag tag = dataFor(player);
        double rate = sampleRate(level, player, tag, PLAYER_SAMPLE_INTERVAL_TICKS, PLAYER_SAMPLE_DISTANCE_SQR);

        double contamination = Math.max(0.0D, tag.getDoubleOr(CONTAMINATION_TAG, 0.0D) * 0.992D);
        contamination += rate * 0.08D;
        if (player.isInWaterOrRain()) {
            contamination *= 0.82D;
        }

        double dose = Math.max(0.0D, tag.getDoubleOr(DOSE_TAG, 0.0D) * 0.9985D) + rate * 0.04D + contamination * 0.02D;
        tag.putDouble(RATE_TAG, rate);
        tag.putDouble(DOSE_TAG, dose);
        tag.putDouble(CONTAMINATION_TAG, contamination);

        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        applySymptoms(player, rate, dose, contamination);
    }

    public static void tickLivingEntity(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player || entity.isDeadOrDying()) {
            return;
        }

        CompoundTag tag = dataFor(entity);
        double rate = sampleRate(level, entity, tag, ENTITY_SAMPLE_INTERVAL_TICKS, ENTITY_SAMPLE_DISTANCE_SQR);
        double contamination = Math.max(0.0D, tag.getDoubleOr(CONTAMINATION_TAG, 0.0D) * 0.994D) + rate * 0.04D;
        double dose = Math.max(0.0D, tag.getDoubleOr(DOSE_TAG, 0.0D) * 0.999D) + rate * 0.02D;
        tag.putDouble(RATE_TAG, rate);
        tag.putDouble(DOSE_TAG, dose);
        tag.putDouble(CONTAMINATION_TAG, contamination);

        applyAmbientSymptoms(entity, rate, dose);
    }

    public static void copyPersistentData(Player original, Player clone) {
        CompoundTag source = dataFor(original);
        CompoundTag target = dataFor(clone);
        target.putDouble(RATE_TAG, source.getDoubleOr(RATE_TAG, 0.0D));
        target.putDouble(DOSE_TAG, source.getDoubleOr(DOSE_TAG, 0.0D));
        target.putDouble(CONTAMINATION_TAG, source.getDoubleOr(CONTAMINATION_TAG, 0.0D));
    }

    public static void removePersistentData(LivingEntity entity) {
        DATA.remove(entity.getUUID());
    }

    public static double getRate(Player player) {
        return dataFor(player).getDoubleOr(RATE_TAG, 0.0D);
    }

    public static double getDose(Player player) {
        return dataFor(player).getDoubleOr(DOSE_TAG, 0.0D);
    }

    public static double getContamination(Player player) {
        return dataFor(player).getDoubleOr(CONTAMINATION_TAG, 0.0D);
    }

    public static double getRawRate(Player player) {
        return getRate(player);
    }

    public static double getProtection(Player player) {
        return 0.0D;
    }

    private static void applySymptoms(ServerPlayer player, double rate, double dose, double contamination) {
        if (rate >= 0.02D) {
            int amplifier = rate >= 0.25D ? 3 : rate >= 0.12D ? 2 : rate >= 0.05D ? 1 : 0;
            player.addEffect(new MobEffectInstance(radiationSicknessHolder(), 120, amplifier, true, false, true));
        }
        if (rate >= 0.02D || dose >= 0.8D) {
            player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 100, 0, true, false, true));
        }
        if (rate >= 0.05D || dose >= 2.0D || contamination >= 0.8D) {
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0, true, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 80, 0, true, false, true));
        }
        if (rate >= 0.12D || dose >= 5.0D) {
            player.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0, true, false, true));
        }
        if (rate >= 0.25D || dose >= 10.0D) {
            player.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0, true, false, true));
            player.hurt(player.damageSources().magic(), (float) Math.min(8.0D, 1.0D + rate * 6.0D));
        }
    }

    private static void applyAmbientSymptoms(LivingEntity entity, double rate, double dose) {
        if (rate >= 0.04D) {
            int amplifier = rate >= 0.10D ? 2 : rate >= 0.06D ? 1 : 0;
            entity.addEffect(new MobEffectInstance(radiationSicknessHolder(), 120, amplifier, true, false, true));
            entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 0, true, false, true));
        }
        if (rate >= 0.10D || dose >= 3.0D) {
            entity.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0, true, false, true));
        }
    }

    private static Holder<MobEffect> radiationSicknessHolder() {
        return BuiltInRegistries.MOB_EFFECT.wrapAsHolder(ModMobEffects.RADIATION_SICKNESS.get());
    }

    private static double sampleRate(ServerLevel level, LivingEntity entity, CompoundTag tag, int intervalTicks, double moveThresholdSqr) {
        int currentTick = entity.tickCount;
        boolean stale = currentTick - tag.getIntOr(LAST_SAMPLE_TICK_TAG, Integer.MIN_VALUE / 2) >= intervalTicks;
        boolean moved = movedFarEnough(entity, tag, moveThresholdSqr);

        if (!stale && !moved) {
            return tag.getDoubleOr(RATE_TAG, 0.0D);
        }

        double rate = RadiationSavedData.get(level).getRadiationAt(level, entity.position());
        tag.putDouble(RATE_TAG, rate);
        tag.putInt(LAST_SAMPLE_TICK_TAG, currentTick);
        tag.putDouble(LAST_SAMPLE_X_TAG, entity.getX());
        tag.putDouble(LAST_SAMPLE_Y_TAG, entity.getY());
        tag.putDouble(LAST_SAMPLE_Z_TAG, entity.getZ());
        return rate;
    }

    private static boolean movedFarEnough(LivingEntity entity, CompoundTag tag, double moveThresholdSqr) {
        if (!tag.contains(LAST_SAMPLE_X_TAG) || !tag.contains(LAST_SAMPLE_Y_TAG) || !tag.contains(LAST_SAMPLE_Z_TAG)) {
            return true;
        }
        double dx = entity.getX() - tag.getDoubleOr(LAST_SAMPLE_X_TAG, entity.getX());
        double dy = entity.getY() - tag.getDoubleOr(LAST_SAMPLE_Y_TAG, entity.getY());
        double dz = entity.getZ() - tag.getDoubleOr(LAST_SAMPLE_Z_TAG, entity.getZ());
        return dx * dx + dy * dy + dz * dz >= moveThresholdSqr;
    }

    private static CompoundTag dataFor(LivingEntity entity) {
        return DATA.computeIfAbsent(entity.getUUID(), ignored -> new CompoundTag());
    }
}
