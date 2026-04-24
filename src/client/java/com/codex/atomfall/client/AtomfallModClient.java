package com.codex.atomfall.client;

import com.codex.atomfall.client.overlay.AtomfallOverlay;
import com.codex.atomfall.client.render.NuclearCloudRenderer;
import com.codex.atomfall.client.render.ShockwaveRingRenderer;
import com.codex.atomfall.client.render.ThermalPulseRingRenderer;
import com.codex.atomfall.registry.ModBlocks;
import com.codex.atomfall.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

public final class AtomfallModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockRenderLayerMap.putBlock(ModBlocks.FUSED_GLASS.get(), ChunkSectionLayer.TRANSLUCENT);
        HudRenderCallback.EVENT.register(AtomfallOverlay::render);
        EntityRendererRegistry.register(ModEntities.SHOCKWAVE_RING.get(), ShockwaveRingRenderer::new);
        EntityRendererRegistry.register(ModEntities.THERMAL_PULSE_RING.get(), ThermalPulseRingRenderer::new);
        EntityRendererRegistry.register(ModEntities.NUCLEAR_CLOUD.get(), NuclearCloudRenderer::new);
    }
}
