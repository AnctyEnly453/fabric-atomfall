package com.codex.atomfall.common.entity;

import com.codex.atomfall.common.world.BlastPhysicsConstants;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class ShockwaveRingEntity extends Entity {
    private static final EntityDataAccessor<Float> DATA_RADIUS = SynchedEntityData.defineId(ShockwaveRingEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_MAX_RADIUS = SynchedEntityData.defineId(ShockwaveRingEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_YIELD = SynchedEntityData.defineId(ShockwaveRingEntity.class, EntityDataSerializers.FLOAT);

    private float previousRadius;

    public ShockwaveRingEntity(EntityType<? extends ShockwaveRingEntity> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
    }

    public void configure(double yieldKt, float maxRadius) {
        this.previousRadius = 0.0F;
        this.entityData.set(DATA_RADIUS, 0.0F);
        this.entityData.set(DATA_MAX_RADIUS, maxRadius);
        this.entityData.set(DATA_YIELD, (float) yieldKt);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_RADIUS, 0.0F);
        builder.define(DATA_MAX_RADIUS, 1.0F);
        builder.define(DATA_YIELD, 1.0F);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        this.previousRadius = input.getFloatOr("PreviousRadius", 0.0F);
        this.entityData.set(DATA_RADIUS, input.getFloatOr("Radius", 0.0F));
        this.entityData.set(DATA_MAX_RADIUS, input.getFloatOr("MaxRadius", 1.0F));
        this.entityData.set(DATA_YIELD, input.getFloatOr("YieldKt", 1.0F));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putFloat("PreviousRadius", this.previousRadius);
        output.putFloat("Radius", this.getRadius());
        output.putFloat("MaxRadius", this.getMaxRadius());
        output.putFloat("YieldKt", this.getYieldKt());
    }

    @Override
    public void tick() {
        super.tick();
        this.previousRadius = this.getRadius();
        float nextRadius = (float) (this.previousRadius + BlastPhysicsConstants.shockFrontSpeed(this.getYieldKt(), this.previousRadius));
        this.entityData.set(DATA_RADIUS, nextRadius);
        if (nextRadius >= this.getMaxRadius() + 40.0F) {
            this.discard();
        }
    }

    public float getRadius() {
        return this.entityData.get(DATA_RADIUS);
    }

    public float getMaxRadius() {
        return this.entityData.get(DATA_MAX_RADIUS);
    }

    public float getYieldKt() {
        return this.entityData.get(DATA_YIELD);
    }

    public float getRenderRadius(float partialTick) {
        return net.minecraft.util.Mth.lerp(partialTick, this.previousRadius, this.getRadius());
    }

    public float getShellWidth() {
        return (float) Math.max(6.0D, BlastPhysicsConstants.shockFrontSpeed(this.getYieldKt(), this.getRadius()) * 1.35D);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource damageSource, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void doWaterSplashEffect() {
    }
}
