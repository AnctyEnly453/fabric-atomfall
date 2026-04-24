package com.codex.atomfall.common.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;

public class RadiationSicknessEffect extends MobEffect {
    public RadiationSicknessEffect() {
        super(MobEffectCategory.HARMFUL, 0x90FF62);
    }

    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        entity.hurt(entity.damageSources().magic(), 1.2F + amplifier);
        if (entity instanceof Player player) {
            player.causeFoodExhaustion(0.004F * (amplifier + 1));
        }
        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        int interval = Math.max(10, 26 - amplifier * 5);
        return duration % interval == 0;
    }
}
