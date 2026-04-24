package com.codex.atomfall.client.render;

import com.codex.atomfall.AtomfallMod;
import com.codex.atomfall.common.entity.NuclearCloudEntity;
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

public final class NuclearCloudRenderer extends EntityRenderer<NuclearCloudEntity, NuclearCloudRenderer.CloudState> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(AtomfallMod.MODID, "textures/entity/nuclear_cloud.png");

    public NuclearCloudRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
        this.shadowStrength = 0.0F;
    }

    @Override
    public CloudState createRenderState() {
        return new CloudState();
    }

    @Override
    public void extractRenderState(NuclearCloudEntity entity, CloudState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.progress = entity.getProgress(partialTick);
        state.yield = entity.getYieldKt();
        state.age = entity.tickCount + partialTick;
        float lifetime = entity.getLifetime();
        state.fade = 1.0F - Math.max(0.0F, (state.age - lifetime * 0.65F) / Math.max(1.0F, lifetime * 0.35F));
    }

    @Override
    public void submit(CloudState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState cameraRenderState) {
        if (state.fade <= 0.01F) {
            return;
        }

        float stemWidth = 8.0F + state.progress * 12.0F + state.yield * 0.08F;
        float stemHeight = 20.0F + state.progress * 34.0F + state.yield * 0.18F;
        float capWidth = 24.0F + state.progress * 36.0F + state.yield * 0.26F;
        float capHeight = 12.0F + state.progress * 18.0F + state.yield * 0.08F;

        for (int i = 0; i < 4; i++) {
            poseStack.pushPose();
            poseStack.translate(0.0D, 5.0D + i * 4.5D, 0.0D);
            poseStack.mulPose(cameraRenderState.orientation);
            poseStack.mulPose(Axis.ZP.rotationDegrees(i * 45.0F + state.age * 0.12F));
            submitQuad(collector, poseStack, stemWidth * (0.88F + i * 0.08F), stemHeight * (0.80F + i * 0.05F), state.fade * (0.18F + i * 0.03F), 138, 132, 124, state.lightCoords);
            poseStack.popPose();
        }

        for (int i = 0; i < 5; i++) {
            poseStack.pushPose();
            poseStack.translate(0.0D, 22.0D + state.progress * 18.0D + i * 1.6D, 0.0D);
            poseStack.mulPose(cameraRenderState.orientation);
            poseStack.mulPose(Axis.ZP.rotationDegrees(i * 16.0F + state.age * 0.16F));
            submitQuad(collector, poseStack, capWidth * (0.84F + i * 0.08F), capHeight * (0.82F + i * 0.06F), state.fade * (0.24F - i * 0.02F), 214, 206, 186, state.lightCoords);
            poseStack.popPose();
        }
    }

    private void submitQuad(SubmitNodeCollector collector, PoseStack poseStack, float width, float height, float alpha, int r, int g, int b, int light) {
        int a = (int) (Math.max(0.0F, alpha) * 255.0F);
        if (a <= 0) {
            return;
        }
        float halfWidth = width * 0.5F;
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(TEXTURE), (pose, consumer) -> {
            vertex(consumer, pose, -halfWidth, 0.0F, 0.0F, 1.0F, r, g, b, a, light);
            vertex(consumer, pose, halfWidth, 0.0F, 1.0F, 1.0F, r, g, b, a, light);
            vertex(consumer, pose, halfWidth, height, 1.0F, 0.0F, r, g, b, a, light);
            vertex(consumer, pose, -halfWidth, height, 0.0F, 0.0F, r, g, b, a, light);
        });
    }

    private void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float u, float v, int r, int g, int b, int a, int light) {
        consumer.addVertex(pose, x, y, 0.0F)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    public static final class CloudState extends EntityRenderState {
        float progress;
        float yield;
        float fade;
        float age;
    }
}
