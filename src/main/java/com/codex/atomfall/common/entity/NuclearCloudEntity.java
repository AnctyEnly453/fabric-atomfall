package com.codex.atomfall.common.entity;

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

public final class NuclearCloudEntity extends Entity {
    private static final EntityDataAccessor<Float> DATA_YIELD = SynchedEntityData.defineId(NuclearCloudEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_LIFETIME = SynchedEntityData.defineId(NuclearCloudEntity.class, EntityDataSerializers.INT);

    public NuclearCloudEntity(EntityType<? extends NuclearCloudEntity> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
    }

    public void configure(float yieldKt, int lifetime) {
        this.entityData.set(DATA_YIELD, yieldKt);
        this.entityData.set(DATA_LIFETIME, lifetime);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_YIELD, 1.0F);
        builder.define(DATA_LIFETIME, 300);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        this.entityData.set(DATA_YIELD, input.getFloatOr("YieldKt", 1.0F));
        this.entityData.set(DATA_LIFETIME, input.getIntOr("Lifetime", 300));
        this.tickCount = input.getIntOr("Age", 0);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putFloat("YieldKt", this.getYieldKt());
        output.putInt("Lifetime", this.getLifetime());
        output.putInt("Age", this.tickCount);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide() && this.tickCount >= this.getLifetime()) {
            this.discard();
        }
    }

    public float getYieldKt() {
        return this.entityData.get(DATA_YIELD);
    }

    public int getLifetime() {
        return this.entityData.get(DATA_LIFETIME);
    }

    public float getProgress(float partialTick) {
        return Math.min(1.0F, (this.tickCount + partialTick) / Math.max(1.0F, this.getLifetime() * 0.35F));
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
