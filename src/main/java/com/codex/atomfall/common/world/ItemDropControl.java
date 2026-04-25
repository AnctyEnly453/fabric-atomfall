package com.codex.atomfall.common.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.gamerules.GameRules;

public final class ItemDropControl {
    private static volatile boolean disabled;

    private ItemDropControl() {
    }

    public static boolean disabled() {
        return disabled;
    }

    public static void setDisabled(MinecraftServer server, boolean value) {
        disabled = value;
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            level.getGameRules().set(GameRules.BLOCK_DROPS, !value, server);
            level.getGameRules().set(GameRules.MOB_DROPS, !value, server);
            level.getGameRules().set(GameRules.ENTITY_DROPS, !value, server);
        }
    }

    public static void discardIfDisabled(ItemEntity entity) {
        if (disabled) {
            entity.discard();
        }
    }
}
