package com.codex.atomfall.client.render;

import com.codex.atomfall.AtomfallMod;
import com.codex.atomfall.common.entity.ThermalPulseRingEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

public final class ThermalPulseRingRenderer extends EntityRenderer<ThermalPulseRingEntity, ThermalPulseRingRenderer.RingState> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(AtomfallMod.MODID, "textures/entity/shockwave_ring.png");

    public ThermalPulseRingRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
        this.shadowStrength = 0.0F;
    }

    @Override
    public RingState createRenderState() {
        return new RingState();
    }

    @Override
    public void extractRenderState(ThermalPulseRingEntity entity, RingState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.radius = entity.getRenderRadius(partialTick);
        state.maxRadius = entity.getMaxRadius();
        state.alpha = Math.max(0.0F, 0.72F * (1.0F - state.radius / Math.max(1.0F, state.maxRadius)));
    }

    @Override
    public void submit(RingState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState) {
        if (state.alpha <= 0.01F) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.0D, 0.10D, 0.0D);
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        submitDisc(collector, poseStack, state.radius * 2.0F, state.alpha * 0.22F, 255, 184, 104, state.lightCoords);
        poseStack.translate(0.0D, 0.0D, -0.001D);
        submitDisc(collector, poseStack, state.radius * 1.4F, state.alpha * 0.12F, 255, 232, 176, state.lightCoords);
        poseStack.popPose();
    }

    private void submitDisc(SubmitNodeCollector collector, PoseStack poseStack, float size, float alpha, int r, int g, int b, int light) {
        int a = (int) (Math.max(0.0F, alpha) * 255.0F);
        if (a <= 0) {
            return;
        }
        float half = size * 0.5F;
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(TEXTURE), (pose, consumer) -> {
            vertex(consumer, pose, -half, -half, 0.0F, 0.0F, 1.0F, r, g, b, a, light);
            vertex(consumer, pose, half, -half, 0.0F, 1.0F, 1.0F, r, g, b, a, light);
            vertex(consumer, pose, half, half, 0.0F, 1.0F, 0.0F, r, g, b, a, light);
            vertex(consumer, pose, -half, half, 0.0F, 0.0F, 0.0F, r, g, b, a, light);
        });
    }

    private void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int r, int g, int b, int alpha, int light) {
        consumer.addVertex(pose, x, y, z)
                .setColor(r, g, b, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    public static final class RingState extends EntityRenderState {
        float radius;
        float maxRadius;
        float alpha;
    }
}
