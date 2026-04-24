package com.codex.atomfall.common.item;

import com.codex.atomfall.common.world.BlastPhysicsConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.Locale;
import java.util.function.Consumer;

public final class BombBlockItem extends BlockItem {
    private static final String YIELD_TAG = "YieldKt";

    public BombBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    public double getStoredYieldKt(ItemStack stack) {
        return BlastPhysicsConstants.clampYield(stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getDoubleOr(YIELD_TAG, BlastPhysicsConstants.DEFAULT_YIELD_KT));
    }

    public static void setStoredYieldKt(ItemStack stack, double yieldKt) {
        double clamped = BlastPhysicsConstants.clampYield(yieldKt);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putDouble(YIELD_TAG, clamped));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable(
                "item.atomfall.atomic_bomb.tooltip",
                String.format(Locale.ROOT, "%.1f", getStoredYieldKt(stack)),
                String.format(Locale.ROOT, "%.1f", BlastPhysicsConstants.MIN_YIELD_KT),
                String.format(Locale.ROOT, "%.0f", BlastPhysicsConstants.MAX_YIELD_KT)
        ).withStyle(ChatFormatting.GRAY));
    }
}
