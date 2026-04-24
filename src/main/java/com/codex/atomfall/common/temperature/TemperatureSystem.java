package com.codex.atomfall.common.temperature;

import com.codex.atomfall.common.item.TemperatureGaugeItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TemperatureSystem {
    private static final Map<UUID, CompoundTag> DATA = new HashMap<>();
    private static final String TEMPERATURE_TAG = "TemperatureC";
    private static final String LAST_SAMPLE_TICK_TAG = "LastSampleTick";
    private static final String LAST_SAMPLE_X_TAG = "LastSampleX";
    private static final String LAST_SAMPLE_Y_TAG = "LastSampleY";
    private static final String LAST_SAMPLE_Z_TAG = "LastSampleZ";
    private static final int PLAYER_SAMPLE_INTERVAL_TICKS = 5;
    private static final int ENTITY_SAMPLE_INTERVAL_TICKS = 20;
    private static final double PLAYER_SAMPLE_DISTANCE_SQR = 1.0D;
    private static final double ENTITY_SAMPLE_DISTANCE_SQR = 4.0D;

    private TemperatureSystem() {
    }

    public static void tickPlayer(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        CompoundTag tag = dataFor(player);
        double absoluteTemperature = sampleTemperature(level, player, tag, PLAYER_SAMPLE_INTERVAL_TICKS, PLAYER_SAMPLE_DISTANCE_SQR);
        tag.putDouble(TEMPERATURE_TAG, absoluteTemperature);

        if (player.getMainHandItem().getItem() instanceof TemperatureGaugeItem || player.getOffhandItem().getItem() instanceof TemperatureGaugeItem) {
            if (player.tickCount % 20 == 0) {
                player.displayClientMessage(TemperatureGaugeItem.createReadoutComponent(absoluteTemperature), true);
            }
        }

        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        applyHeatEffects(player, absoluteTemperature);
    }

    public static void tickLivingEntity(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || entity instanceof Player || entity.isDeadOrDying()) {
            return;
        }

        CompoundTag tag = dataFor(entity);
        double absoluteTemperature = sampleTemperature(level, entity, tag, ENTITY_SAMPLE_INTERVAL_TICKS, ENTITY_SAMPLE_DISTANCE_SQR);
        tag.putDouble(TEMPERATURE_TAG, absoluteTemperature);
        applyHeatEffects(entity, absoluteTemperature);
    }

    public static double getTemperature(Player player) {
        return dataFor(player).getDoubleOr(TEMPERATURE_TAG, TemperatureMaterialRules.AMBIENT_C);
    }

    public static void copyPersistentData(Player original, Player clone) {
        dataFor(clone).putDouble(TEMPERATURE_TAG, getTemperature(original));
    }

    public static void removePersistentData(LivingEntity entity) {
        DATA.remove(entity.getUUID());
    }

    private static void applyHeatEffects(LivingEntity entity, double temperature) {
        if (temperature >= 60.0D) {
            entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0, true, false, true));
        }
        if (temperature >= 110.0D) {
            entity.addEffect(new MobEffectInstance(MobEffects.HUNGER, 60, 0, true, false, true));
        }
        if (temperature >= 220.0D) {
            entity.igniteForSeconds(3.0F);
            entity.hurt(entity.damageSources().onFire(), 1.2F);
        }
        if (temperature >= 420.0D) {
            entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, true, false, true));
            entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0, true, false, true));
            entity.igniteForSeconds(6.0F);
            entity.hurt(entity.damageSources().lava(), 2.8F);
        }
    }

    private static double sampleTemperature(ServerLevel level, LivingEntity entity, CompoundTag tag, int intervalTicks, double moveThresholdSqr) {
        int currentTick = entity.tickCount;
        boolean stale = currentTick - tag.getIntOr(LAST_SAMPLE_TICK_TAG, Integer.MIN_VALUE / 2) >= intervalTicks;
        boolean moved = movedFarEnough(entity, tag, moveThresholdSqr);

        if (!stale && !moved) {
            return tag.getDoubleOr(TEMPERATURE_TAG, TemperatureMaterialRules.AMBIENT_C);
        }

        double absoluteTemperature = TemperatureSavedData.get(level).getTemperatureAt(level, entity.position());
        tag.putDouble(TEMPERATURE_TAG, absoluteTemperature);
        tag.putInt(LAST_SAMPLE_TICK_TAG, currentTick);
        tag.putDouble(LAST_SAMPLE_X_TAG, entity.getX());
        tag.putDouble(LAST_SAMPLE_Y_TAG, entity.getY());
        tag.putDouble(LAST_SAMPLE_Z_TAG, entity.getZ());
        return absoluteTemperature;
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
