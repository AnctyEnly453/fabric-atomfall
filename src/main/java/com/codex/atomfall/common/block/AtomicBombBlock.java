package com.codex.atomfall.common.block;

import com.codex.atomfall.common.block.entity.AtomicBombBlockEntity;
import com.codex.atomfall.common.item.BombBlockItem;
import com.codex.atomfall.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public final class AtomicBombBlock extends BaseEntityBlock implements EntityBlock {
    public static final BooleanProperty ARMED = BlockStateProperties.LIT;
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    private final MapCodec<AtomicBombBlock> codec;

    public AtomicBombBlock(Properties properties) {
        super(properties);
        this.codec = simpleCodec(AtomicBombBlock::new);
        this.registerDefaultState(this.stateDefinition.any().setValue(ARMED, false).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return this.codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ARMED, FACING);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(ARMED, false);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AtomicBombBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.ATOMIC_BOMB.get(), AtomicBombBlockEntity::serverTick);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!oldState.is(this) && level.hasNeighborSignal(pos)) {
            arm(level, pos);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston) {
        if (level.hasNeighborSignal(pos)) {
            arm(level, pos);
        }
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.getBlockEntity(pos) instanceof AtomicBombBlockEntity bomb && stack.getItem() instanceof BombBlockItem bombItem) {
            bomb.setYieldKt(bombItem.getStoredYieldKt(stack));
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof AtomicBombBlockEntity bomb)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            player.displayClientMessage(bomb.createYieldReadout().withStyle(ChatFormatting.GOLD), false);
        }
        return sidedSuccess(level);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(stack.is(Items.FLINT_AND_STEEL) || stack.getItem() instanceof FireChargeItem)) {
            return InteractionResult.PASS;
        }
        arm(level, pos);
        if (!player.getAbilities().instabuild) {
            if (stack.is(Items.FLINT_AND_STEEL)) {
                stack.hurtAndBreak(1, player, hand);
            } else {
                stack.shrink(1);
            }
        }
        return sidedSuccess(level);
    }

    @Override
    public boolean dropFromExplosion(net.minecraft.world.level.Explosion explosion) {
        return false;
    }

    public static void arm(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof AtomicBombBlockEntity bomb)) {
            return;
        }
        if (!bomb.arm()) {
            return;
        }
        level.setBlock(pos, level.getBlockState(pos).setValue(ARMED, true), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.CREEPER_PRIMED, SoundSource.BLOCKS, 1.0F, 0.55F);
    }

    private static InteractionResult sidedSuccess(Level level) {
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }
}
