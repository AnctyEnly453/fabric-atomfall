package com.codex.atomfall.client.overlay;

import com.codex.atomfall.common.entity.NuclearCloudEntity;
import com.codex.atomfall.common.entity.ShockwaveRingEntity;
import com.codex.atomfall.common.entity.ThermalPulseRingEntity;
import com.codex.atomfall.registry.ModMobEffects;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.List;

public final class AtomfallOverlay {
    private static int frameCounter;
    private static List<NuclearCloudEntity> cachedClouds = Collections.emptyList();
    private static List<ShockwaveRingEntity> cachedShockwaves = Collections.emptyList();
    private static List<ThermalPulseRingEntity> cachedThermalRings = Collections.emptyList();

    private AtomfallOverlay() {
    }

    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        int width = guiGraphics.guiWidth();
        int height = guiGraphics.guiHeight();

        if (frameCounter++ % 2 == 0) {
            AABB searchBox = minecraft.player.getBoundingBox().inflate(300.0D);
            cachedClouds = minecraft.level.getEntitiesOfClass(NuclearCloudEntity.class, searchBox);
            cachedShockwaves = minecraft.level.getEntitiesOfClass(ShockwaveRingEntity.class, searchBox);
            cachedThermalRings = minecraft.level.getEntitiesOfClass(ThermalPulseRingEntity.class, searchBox);
        }

        float flash = cloudFlash(minecraft, partialTick);
        if (flash > 0.01F) {
            guiGraphics.fill(0, 0, width, height, argb((int) (flash * 255.0F), 255, 248, 228));
        }

        float heat = thermalFlash(minecraft, partialTick);
        if (minecraft.player.isOnFire()) {
            heat = Math.max(heat, 0.30F);
        }
        if (heat > 0.01F) {
            int edge = argb((int) (Math.min(0.75F, heat * 1.2F) * 255.0F), 255, 138, 68);
            int bandX = Math.max(22, width / 10);
            int bandY = Math.max(18, height / 10);
            guiGraphics.fill(0, 0, width, bandY, edge);
            guiGraphics.fill(0, height - bandY, width, height, edge);
            guiGraphics.fill(0, 0, bandX, height, edge);
            guiGraphics.fill(width - bandX, 0, width, height, edge);
        }

        float shock = shockArrival(minecraft, partialTick);
        if (shock > 0.01F) {
            guiGraphics.fill(0, 0, width, height, argb((int) (shock * 120.0F), 220, 214, 206));
        }

        float radiation = 0.0F;
        Holder<MobEffect> radiationHolder = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(ModMobEffects.RADIATION_SICKNESS.get());
        if (minecraft.player.hasEffect(radiationHolder)) {
            radiation = 0.25F + 0.08F * Mth.sin((minecraft.player.tickCount + partialTick) * 0.24F);
        }
        if (radiation > 0.01F) {
            int edge = argb((int) (radiation * 255.0F), 92, 206, 108);
            int bandX = Math.max(18, width / 12);
            int bandY = Math.max(16, height / 12);
            guiGraphics.fill(0, 0, width, bandY, edge);
            guiGraphics.fill(0, height - bandY, width, height, edge);
            guiGraphics.fill(0, 0, bandX, height, edge);
            guiGraphics.fill(width - bandX, 0, width, height, edge);
        }
    }

    private static float cloudFlash(Minecraft minecraft, float partialTick) {
        float strongest = 0.0F;
        for (NuclearCloudEntity cloud : cachedClouds) {
            float ageFade = 1.0F - Math.min(1.0F, (cloud.tickCount + partialTick) / 16.0F);
            if (ageFade <= 0.0F) {
                continue;
            }
            double distance = Math.sqrt(cloud.distanceToSqr(minecraft.player));
            float distanceFade = 1.0F - Mth.clamp((float) (distance / (60.0F + cloud.getYieldKt() * 14.0F)), 0.0F, 1.0F);
            strongest = Math.max(strongest, ageFade * distanceFade);
        }
        return strongest;
    }

    private static float thermalFlash(Minecraft minecraft, float partialTick) {
        float strongest = 0.0F;
        for (ThermalPulseRingEntity ring : cachedThermalRings) {
            double distance = horizontalDistance(minecraft, ring.getX(), ring.getZ());
            float diff = Math.abs((float) distance - ring.getRenderRadius(partialTick));
            float width = Math.max(14.0F, ring.getCoreRadius() * 0.12F);
            if (diff > width) {
                continue;
            }
            float proximity = 1.0F - diff / width;
            strongest = Math.max(strongest, proximity * 0.55F);
        }
        return strongest;
    }

    private static float shockArrival(Minecraft minecraft, float partialTick) {
        float strongest = 0.0F;
        for (ShockwaveRingEntity ring : cachedShockwaves) {
            double distance = horizontalDistance(minecraft, ring.getX(), ring.getZ());
            float diff = Math.abs((float) distance - ring.getRenderRadius(partialTick));
            float width = ring.getShellWidth() * 0.9F;
            if (diff > width) {
                continue;
            }
            strongest = Math.max(strongest, 1.0F - diff / width);
        }
        return strongest * 0.42F;
    }

    private static double horizontalDistance(Minecraft minecraft, double x, double z) {
        double dx = minecraft.player.getX() - x;
        double dz = minecraft.player.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int argb(int a, int r, int g, int b) {
        return ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
    }
}
