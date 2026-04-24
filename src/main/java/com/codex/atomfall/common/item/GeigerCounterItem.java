package com.codex.atomfall.common.item;

import com.codex.atomfall.common.radiation.RadiationSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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

public final class GeigerCounterItem extends Item {
    public GeigerCounterItem(Properties properties) {
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
        double rawRate = RadiationSystem.getRawRate(serverPlayer);
        double dose = RadiationSystem.getDose(serverPlayer);
        double contamination = RadiationSystem.getContamination(serverPlayer);
        serverPlayer.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.42F, (float) Mth.clamp(0.65D + rawRate * 3.0D, 0.65D, 1.9D));
        serverPlayer.displayClientMessage(Component.translatable(
                "item.atomfall.geiger_counter.readout",
                String.format(Locale.ROOT, "%.3f", rawRate),
                String.format(Locale.ROOT, "%.2f", dose),
                String.format(Locale.ROOT, "%.2f", contamination)
        ).withStyle(ChatFormatting.GREEN), true);
        return InteractionResult.SUCCESS_SERVER;
    }
}
