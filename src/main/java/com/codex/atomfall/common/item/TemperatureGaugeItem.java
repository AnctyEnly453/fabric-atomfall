package com.codex.atomfall.common.item;

import com.codex.atomfall.common.temperature.TemperatureMaterialRules;
import com.codex.atomfall.common.temperature.TemperatureSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Locale;

public final class TemperatureGaugeItem extends Item {
    public TemperatureGaugeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, net.minecraft.world.entity.player.Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        player.awardStat(Stats.ITEM_USED.get(this));
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        ServerPlayer serverPlayer = (ServerPlayer) player;
        double temperature = TemperatureSystem.getTemperature(serverPlayer);
        serverPlayer.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, (float) Mth.clamp(0.7D + temperature / 1200.0D, 0.7D, 1.8D));
        serverPlayer.displayClientMessage(createReadoutComponent(temperature).withStyle(ChatFormatting.GOLD), true);
        return InteractionResult.SUCCESS_SERVER;
    }

    public static MutableComponent createReadoutComponent(double temperature) {
        return Component.translatable(
                "item.atomfall.temperature_gauge.readout",
                String.format(Locale.ROOT, "%.0f", temperature),
                Component.translatable(stageKey(temperature))
        );
    }

    private static String stageKey(double temperature) {
        if (temperature >= TemperatureMaterialRules.SAND_GLASSING_POINT_C) {
            return "item.atomfall.temperature_gauge.stage.vitrification";
        }
        if (temperature >= TemperatureMaterialRules.GLASS_SOFTENING_POINT_C) {
            return "item.atomfall.temperature_gauge.stage.softening";
        }
        if (temperature >= TemperatureMaterialRules.WOOD_IGNITION_POINT_C) {
            return "item.atomfall.temperature_gauge.stage.ignition";
        }
        if (temperature >= TemperatureMaterialRules.WATER_BOILING_POINT_C) {
            return "item.atomfall.temperature_gauge.stage.boiling";
        }
        return "item.atomfall.temperature_gauge.stage.warm";
    }
}
