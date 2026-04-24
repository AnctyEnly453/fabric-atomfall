package com.codex.atomfall.common.block.entity;

import com.codex.atomfall.common.world.BlastPhysicsConstants;
import com.codex.atomfall.common.world.NuclearBlast;
import com.codex.atomfall.registry.ModBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Locale;

public final class AtomicBombBlockEntity extends BlockEntity {
    private static final int DEFAULT_FUSE_TICKS = 100;

    private boolean armed;
    private int fuseTicks;
    private double yieldKt = BlastPhysicsConstants.DEFAULT_YIELD_KT;

    public AtomicBombBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.ATOMIC_BOMB.get(), pos, blockState);
    }

    public boolean arm() {
        if (this.armed) {
            return false;
        }
        this.armed = true;
        this.fuseTicks = DEFAULT_FUSE_TICKS;
        setChanged();
        return true;
    }

    public void forceDetonate(ServerLevel level) {
        this.armed = false;
        level.removeBlock(this.worldPosition, false);
        NuclearBlast.detonate(level, this.worldPosition, this.yieldKt);
    }

    public boolean adjustYield(double deltaKt) {
        if (this.armed) {
            return false;
        }
        double next = BlastPhysicsConstants.clampYield(this.yieldKt + deltaKt);
        if (Math.abs(next - this.yieldKt) < 1.0E-6D) {
            return false;
        }
        this.yieldKt = next;
        setChanged();
        return true;
    }

    public void setYieldKt(double yieldKt) {
        this.yieldKt = BlastPhysicsConstants.clampYield(yieldKt);
        setChanged();
    }

    public MutableComponent createYieldReadout() {
        NuclearBlast.BlastGeometry geometry = NuclearBlast.BlastGeometry.fromYield(this.yieldKt);
        return Component.translatable(
                "block.atomfall.atomic_bomb.readout",
                String.format(Locale.ROOT, "%.1f", this.yieldKt),
                String.format(Locale.ROOT, "%.0f", geometry.fireballRadius()),
                String.format(Locale.ROOT, "%.0f", geometry.craterRadius()),
                String.format(Locale.ROOT, "%.0f", geometry.shockSevereRadius()),
                String.format(Locale.ROOT, "%.0f", geometry.shockSurfaceRadius()),
                String.format(Locale.ROOT, "%.0f", geometry.thermalRadius())
        ).withStyle(this.armed ? ChatFormatting.RED : ChatFormatting.GOLD);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, AtomicBombBlockEntity bomb) {
        if (!(level instanceof ServerLevel serverLevel) || !bomb.armed) {
            return;
        }

        if (bomb.fuseTicks % 10 == 0) {
            float pitch = 0.7F + (DEFAULT_FUSE_TICKS - bomb.fuseTicks) / (float) DEFAULT_FUSE_TICKS * 0.8F;
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 0.6F, pitch);
        }

        serverLevel.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D, 2, 0.10D, 0.05D, 0.10D, 0.004D);
        serverLevel.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D, 1, 0.08D, 0.03D, 0.08D, 0.002D);

        bomb.fuseTicks--;
        if (bomb.fuseTicks % 10 == 0 || bomb.fuseTicks <= 5) {
            bomb.setChanged();
        }
        if (bomb.fuseTicks > 0) {
            return;
        }

        bomb.armed = false;
        serverLevel.removeBlock(pos, false);
        NuclearBlast.detonate(serverLevel, pos, bomb.yieldKt);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("Armed", this.armed);
        output.putInt("FuseTicks", this.fuseTicks);
        output.putDouble("YieldKt", this.yieldKt);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.armed = input.getBooleanOr("Armed", false);
        this.fuseTicks = input.getIntOr("FuseTicks", DEFAULT_FUSE_TICKS);
        this.yieldKt = BlastPhysicsConstants.clampYield(input.getDoubleOr("YieldKt", BlastPhysicsConstants.DEFAULT_YIELD_KT));
    }
}
