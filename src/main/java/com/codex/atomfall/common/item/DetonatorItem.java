package com.codex.atomfall.common.item;

import com.codex.atomfall.common.block.AtomicBombBlock;
import com.codex.atomfall.common.block.entity.AtomicBombBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class DetonatorItem extends Item {
    private static final String LINKS_TAG = "Links";
    private static final String MODE_TAG = "Mode";
    private static final String STEP_TAG = "Step";
    private static final double[] STEPS = {0.1D, 0.5D, 1.0D, 5.0D};

    public DetonatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof AtomicBombBlock)) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(context.getLevel() instanceof ServerLevel level) || !(level.getBlockEntity(context.getClickedPos()) instanceof AtomicBombBlockEntity bomb)) {
            return InteractionResult.PASS;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.SUCCESS_SERVER;
        }

        ItemStack stack = context.getItemInHand();
        return switch (readMode(stack)) {
            case LINK -> {
                boolean linked = toggleBinding(stack, level, context.getClickedPos());
                player.displayClientMessage(Component.translatable(
                        linked ? "item.atomfall.detonator.bound" : "item.atomfall.detonator.unbound",
                        context.getClickedPos().toShortString()
                ).withStyle(linked ? ChatFormatting.YELLOW : ChatFormatting.GRAY), true);
                level.playSound(null, context.getClickedPos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.8F, linked ? 1.1F : 0.85F);
                yield InteractionResult.SUCCESS_SERVER;
            }
            case YIELD_UP, YIELD_DOWN -> {
                double step = readStep(stack);
                double delta = readMode(stack) == ControlMode.YIELD_UP ? step : -step;
                boolean changed = bomb.adjustYield(delta);
                player.displayClientMessage(
                        changed ? bomb.createYieldReadout() : Component.translatable("item.atomfall.detonator.adjust_locked").withStyle(ChatFormatting.RED),
                        true
                );
                level.playSound(null, context.getClickedPos(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.6F, delta > 0.0D ? 1.1F : 0.8F);
                yield InteractionResult.SUCCESS_SERVER;
            }
        };
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        player.awardStat(Stats.ITEM_USED.get(this));
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            ControlMode next = readMode(stack).next();
            writeMode(stack, next);
            player.displayClientMessage(Component.translatable("item.atomfall.detonator.mode", next.translationKey()).withStyle(ChatFormatting.AQUA), true);
            return InteractionResult.SUCCESS_SERVER;
        }

        ControlMode mode = readMode(stack);
        if (mode == ControlMode.LINK) {
            int detonated = triggerLinkedBombs((ServerLevel) level, stack);
            player.displayClientMessage(Component.translatable("item.atomfall.detonator.triggered", detonated).withStyle(ChatFormatting.RED), true);
            player.playSound(SoundEvents.LEVER_CLICK, 0.8F, detonated > 0 ? 0.55F : 0.92F);
            return InteractionResult.SUCCESS_SERVER;
        }

        double nextStep = nextStep(readStep(stack));
        writeStep(stack, nextStep);
        player.displayClientMessage(Component.translatable("item.atomfall.detonator.step", String.format(Locale.ROOT, "%.1f", nextStep)).withStyle(ChatFormatting.YELLOW), true);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("item.atomfall.detonator.tooltip.mode", readMode(stack).translationKey()).withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("item.atomfall.detonator.tooltip.step", String.format(Locale.ROOT, "%.1f", readStep(stack))).withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("item.atomfall.detonator.link_count", readLinks(stack).size()).withStyle(ChatFormatting.GRAY));
    }

    private static int triggerLinkedBombs(ServerLevel level, ItemStack stack) {
        MinecraftServer server = level.getServer();
        List<RemoteLink> links = readLinks(stack);
        int detonated = 0;

        Iterator<RemoteLink> iterator = links.iterator();
        while (iterator.hasNext()) {
            RemoteLink link = iterator.next();
            ServerLevel target = server.getLevel(link.dimension());
            if (target == null || !(target.getBlockEntity(link.pos()) instanceof AtomicBombBlockEntity bomb)) {
                iterator.remove();
                continue;
            }
            bomb.forceDetonate(target);
            iterator.remove();
            detonated++;
        }

        writeLinks(stack, links);
        return detonated;
    }

    private static boolean toggleBinding(ItemStack stack, ServerLevel level, BlockPos pos) {
        List<RemoteLink> links = readLinks(stack);
        ResourceKey<Level> dimension = level.dimension();
        for (Iterator<RemoteLink> iterator = links.iterator(); iterator.hasNext(); ) {
            RemoteLink link = iterator.next();
            if (link.dimension().equals(dimension) && link.pos().equals(pos)) {
                iterator.remove();
                writeLinks(stack, links);
                return false;
            }
        }
        links.add(new RemoteLink(dimension, pos.immutable()));
        writeLinks(stack, links);
        return true;
    }

    private static ControlMode readMode(ItemStack stack) {
        String mode = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr(MODE_TAG, ControlMode.LINK.name());
        return ControlMode.byName(mode);
    }

    private static void writeMode(ItemStack stack, ControlMode mode) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(MODE_TAG, mode.name()));
    }

    private static double readStep(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getDoubleOr(STEP_TAG, STEPS[1]);
    }

    private static void writeStep(ItemStack stack, double step) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putDouble(STEP_TAG, step));
    }

    private static double nextStep(double current) {
        for (int i = 0; i < STEPS.length; i++) {
            if (Math.abs(STEPS[i] - current) < 1.0E-6D) {
                return STEPS[(i + 1) % STEPS.length];
            }
        }
        return STEPS[0];
    }

    private static List<RemoteLink> readLinks(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        ListTag list = tag.getListOrEmpty(LINKS_TAG);
        List<RemoteLink> links = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i).orElse(new CompoundTag());
            Identifier id = Identifier.tryParse(entry.getStringOr("Dimension", ""));
            if (id == null) {
                continue;
            }
            long posLong = entry.getLongOr("Pos", Long.MIN_VALUE);
            if (posLong == Long.MIN_VALUE) {
                continue;
            }
            links.add(new RemoteLink(ResourceKey.create(Registries.DIMENSION, id), BlockPos.of(posLong)));
        }
        return links;
    }

    private static void writeLinks(ItemStack stack, List<RemoteLink> links) {
        ListTag list = new ListTag();
        for (RemoteLink link : links) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Dimension", link.dimension().identifier().toString());
            entry.putLong("Pos", link.pos().asLong());
            list.add(entry);
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(LINKS_TAG, list));
    }

    private record RemoteLink(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private enum ControlMode {
        LINK("item.atomfall.detonator.mode.link"),
        YIELD_UP("item.atomfall.detonator.mode.up"),
        YIELD_DOWN("item.atomfall.detonator.mode.down");

        private final String translationKey;

        ControlMode(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return this.translationKey;
        }

        public ControlMode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static ControlMode byName(String name) {
            for (ControlMode value : values()) {
                if (value.name().equalsIgnoreCase(name)) {
                    return value;
                }
            }
            return LINK;
        }
    }
}
