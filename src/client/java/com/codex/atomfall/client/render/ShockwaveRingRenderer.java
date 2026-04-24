package com.codex.atomfall.client.render;

import com.codex.atomfall.AtomfallMod;
import com.codex.atomfall.common.entity.ShockwaveRingEntity;
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
import net.minecraft.util.Mth;

public final class ShockwaveRingRenderer extends EntityRenderer<ShockwaveRingEntity, ShockwaveRingRenderer.RingState> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(AtomfallMod.MODID, "textures/entity/shockwave_ring.png");

    public ShockwaveRingRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
        this.shadowStrength = 0.0F;
    }

    @Override
    public RingState createRenderState() {
        return new RingState();
    }

    @Override
    public void extractRenderState(ShockwaveRingEntity entity, RingState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.radius = entity.getRenderRadius(partialTick);
        state.maxRadius = entity.getMaxRadius();
        state.shellWidth = entity.getShellWidth();
        state.alpha = Math.max(0.0F, 0.55F * (1.0F - state.radius / Math.max(1.0F, state.maxRadius)));
    }

    @Override
    public void submit(RingState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState) {
        if (state.alpha <= 0.01F) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.0D, 0.08D, 0.0D);
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        submitDisc(collector, poseStack, state.radius * 2.0F, state.alpha * 0.24F, 226, 222, 214, state.lightCoords);
        poseStack.popPose();

        int segments = Mth.clamp(Mth.ceil(state.radius * 0.18F), 24, 144);
        float outer = state.radius + state.shellWidth * 0.22F;
        float inner = Math.max(0.0F, state.radius - state.shellWidth * 0.60F);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(TEXTURE), (pose, consumer) -> {
            for (int i = 0; i < segments; i++) {
                float t0 = i / (float) segments;
                float t1 = (i + 1) / (float) segments;
                double a0 = t0 * Mth.TWO_PI;
                double a1 = t1 * Mth.TWO_PI;
                float wall0 = 1.8F + 3.6F * Mth.sin(state.ageInTicks * 0.08F + i * 0.32F) * 0.12F;
                float wall1 = 1.8F + 3.6F * Mth.sin(state.ageInTicks * 0.08F + (i + 1) * 0.32F) * 0.12F;
                float ox0 = (float) (Math.cos(a0) * outer);
                float oz0 = (float) (Math.sin(a0) * outer);
                float ox1 = (float) (Math.cos(a1) * outer);
                float oz1 = (float) (Math.sin(a1) * outer);
                float ix0 = (float) (Math.cos(a0) * inner);
                float iz0 = (float) (Math.sin(a0) * inner);
                float ix1 = (float) (Math.cos(a1) * inner);
                float iz1 = (float) (Math.sin(a1) * inner);
                submitWall(consumer, pose, ox0, oz0, ox1, oz1, 0.0F, wall0, wall1, t0, t1, 226, 222, 214, state.alpha, state.alpha * 0.16F, state.lightCoords);
                submitWall(consumer, pose, ix1, iz1, ix0, iz0, 0.0F, wall1 * 0.75F, wall0 * 0.75F, t0, t1, 178, 170, 158, state.alpha * 0.32F, state.alpha * 0.08F, state.lightCoords);
            }
        });
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

    private void submitWall(VertexConsumer consumer, PoseStack.Pose pose, float x0, float z0, float x1, float z1, float baseY, float topY0, float topY1, float u0, float u1, int r, int g, int b, float baseAlpha, float topAlpha, int light) {
        vertex(consumer, pose, x0, baseY, z0, u0, 1.0F, r, g, b, (int) (baseAlpha * 255.0F), light);
        vertex(consumer, pose, x1, baseY, z1, u1, 1.0F, r, g, b, (int) (baseAlpha * 255.0F), light);
        vertex(consumer, pose, x1, topY1, z1, u1, 0.0F, r, g, b, (int) (topAlpha * 255.0F), light);
        vertex(consumer, pose, x0, topY0, z0, u0, 0.0F, r, g, b, (int) (topAlpha * 255.0F), light);
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
        float shellWidth;
        float alpha;
    }
}
