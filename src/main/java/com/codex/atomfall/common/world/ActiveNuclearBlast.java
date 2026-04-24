package com.codex.atomfall.common.world;

import com.codex.atomfall.AtomfallMod;
import com.codex.atomfall.common.temperature.TemperatureMaterialRules;
import com.codex.atomfall.registry.ModBlocks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-detonation state machine.
 *
 * Heavy work is split into:
 * 1. crater / fireball excavation with a fixed block budget
 * 2. shockwave shell sampling with sector fronts
 * 3. small structural and surface edits only around the active shell
 */
public final class ActiveNuclearBlast {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int UPDATE_CLIENTS_ONLY = 2;
    private static final int PARALLEL_MIN_SNAPSHOTS = 120;
    private static final ExecutorService COMPUTE_EXECUTOR = createComputeExecutor();

    private final BlockPos origin;
    private final Vec3 center;
    private final NuclearBlast.BlastGeometry geometry;
    private final SurfaceHeightCache initialSurface;
    private final float[] sectorFront;
    private final float[] previousSectorFront;
    private final float[] sectorEnergy;
    private final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap processedSurfaceColumns = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
    private final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap processedStructureColumns = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
    private final LongOpenHashSet deferredColumns = new LongOpenHashSet();
    private final Set<UUID> heardShockPlayers = new HashSet<>();
    private final java.util.ArrayList<PendingEdit> deferredEdits = new java.util.ArrayList<>();

    private boolean flashApplied;
    private boolean craterFinished;
    private boolean craterCollapsed;
    private CompletableFuture<List<BlockEdit>> craterComputeFuture;
    private final ArrayList<PendingShockBatch> pendingShockBatches = new ArrayList<>();
    private final ArrayList<List<ShockTarget>> readyShockTargets = new ArrayList<>();

    private record PendingEdit(int x, int z, int radial, double psi, double angle, boolean structural) {
        private static final Codec<PendingEdit> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.INT.fieldOf("x").forGetter(PendingEdit::x),
                        Codec.INT.fieldOf("z").forGetter(PendingEdit::z),
                        Codec.INT.fieldOf("radial").forGetter(PendingEdit::radial),
                        Codec.DOUBLE.fieldOf("psi").forGetter(PendingEdit::psi),
                        Codec.DOUBLE.fieldOf("angle").forGetter(PendingEdit::angle),
                        Codec.BOOL.fieldOf("structural").forGetter(PendingEdit::structural)
                ).apply(instance, PendingEdit::new)
        );
    }

    private record PendingShockBatch(CompletableFuture<List<BlockEdit>> editsFuture, List<ShockTarget> targets) {
    }

    private record BlockEdit(long packedPos, BlockState newState, boolean remove) {
        boolean apply(ServerLevel level, BlockPos.MutableBlockPos mutable) {
            mutable.set(BlockPos.getX(this.packedPos), BlockPos.getY(this.packedPos), BlockPos.getZ(this.packedPos));
            BlockState current = level.getBlockState(mutable);
            if (current.isAir() || current.is(Blocks.BEDROCK) || current.hasBlockEntity()) {
                return false;
            }
            if (this.remove) {
                if (current.isAir()) {
                    return false;
                }
                level.setBlock(mutable, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS_ONLY);
                return true;
            } else {
                if (current == this.newState) {
                    return false;
                }
                level.setBlock(mutable, this.newState, UPDATE_CLIENTS_ONLY);
                return true;
            }
        }
    }

    private record CraterColumnSnapshot(int x, int z, int initialY, int floorY, int fireballRoof, BlockState floorState) {
    }
    private int craterCursorX;
    private int craterCursorZ;
    private int ageTicks;
    private double averageFront;
    private double previousAverageFront;

    private final ArrayList<BlockEdit> craterEditQueue = new ArrayList<>();
    private boolean craterEditsPrepared;
    private final ArrayList<BlockEdit> shockEditQueue = new ArrayList<>();

    private record ShockTarget(int x, int z, int radial, double psi, double angle, boolean needsSurface, boolean needsStructural) {
        private static final Codec<ShockTarget> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.INT.fieldOf("x").forGetter(ShockTarget::x),
                        Codec.INT.fieldOf("z").forGetter(ShockTarget::z),
                        Codec.INT.fieldOf("radial").forGetter(ShockTarget::radial),
                        Codec.DOUBLE.fieldOf("psi").forGetter(ShockTarget::psi),
                        Codec.DOUBLE.fieldOf("angle").forGetter(ShockTarget::angle),
                        Codec.BOOL.fieldOf("needs_surface").forGetter(ShockTarget::needsSurface),
                        Codec.BOOL.fieldOf("needs_structural").forGetter(ShockTarget::needsStructural)
                ).apply(instance, ShockTarget::new)
        );
    }

    record BlockEditState(long packedPos, boolean remove, BlockState newState) {
        static final Codec<BlockEditState> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.LONG.fieldOf("packed_pos").forGetter(BlockEditState::packedPos),
                        Codec.BOOL.fieldOf("remove").forGetter(BlockEditState::remove),
                        BlockState.CODEC.optionalFieldOf("new_state", Blocks.AIR.defaultBlockState()).forGetter(state -> state.newState == null ? Blocks.AIR.defaultBlockState() : state.newState)
                ).apply(instance, (packedPos, remove, newState) -> new BlockEditState(packedPos, remove, remove ? null : newState))
        );
    }

    record ColumnPsiEntry(long column, double psi) {
        static final Codec<ColumnPsiEntry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.LONG.fieldOf("column").forGetter(ColumnPsiEntry::column),
                        Codec.DOUBLE.fieldOf("psi").forGetter(ColumnPsiEntry::psi)
                ).apply(instance, ColumnPsiEntry::new)
        );
    }

    record SurfaceState(List<SurfaceHeightCache.Entry> surfaceHeights,
                        List<ColumnPsiEntry> processedSurfaceColumns,
                        List<ColumnPsiEntry> processedStructureColumns) {
        static final Codec<SurfaceState> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        SurfaceHeightCache.Entry.CODEC.listOf().fieldOf("surface_heights").forGetter(SurfaceState::surfaceHeights),
                        ColumnPsiEntry.CODEC.listOf().fieldOf("processed_surface_columns").forGetter(SurfaceState::processedSurfaceColumns),
                        ColumnPsiEntry.CODEC.listOf().fieldOf("processed_structure_columns").forGetter(SurfaceState::processedStructureColumns)
                ).apply(instance, SurfaceState::new)
        );
    }

    record WorkState(List<PendingEdit> deferredEdits,
                     List<ShockTarget> pendingShockTargets,
                     List<ShockTarget> readyShockTargets,
                     List<BlockEditState> craterEdits,
                     List<BlockEditState> shockEdits) {
        static final Codec<WorkState> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        PendingEdit.CODEC.listOf().fieldOf("deferred_edits").forGetter(WorkState::deferredEdits),
                        ShockTarget.CODEC.listOf().fieldOf("pending_shock_targets").forGetter(WorkState::pendingShockTargets),
                        ShockTarget.CODEC.listOf().fieldOf("ready_shock_targets").forGetter(WorkState::readyShockTargets),
                        BlockEditState.CODEC.listOf().fieldOf("crater_edits").forGetter(WorkState::craterEdits),
                        BlockEditState.CODEC.listOf().fieldOf("shock_edits").forGetter(WorkState::shockEdits)
                ).apply(instance, WorkState::new)
        );
    }

    static final class PersistenceState {
        static final Codec<PersistenceState> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        BlockPos.CODEC.fieldOf("origin").forGetter(PersistenceState::origin),
                        Codec.DOUBLE.fieldOf("yield_kt").forGetter(PersistenceState::yieldKt),
                        Codec.INT.fieldOf("age_ticks").forGetter(PersistenceState::ageTicks),
                        Codec.BOOL.fieldOf("flash_applied").forGetter(PersistenceState::flashApplied),
                        Codec.BOOL.fieldOf("crater_finished").forGetter(PersistenceState::craterFinished),
                        Codec.BOOL.fieldOf("crater_needs_rebuild").forGetter(PersistenceState::craterNeedsRebuild),
                        Codec.FLOAT.listOf().fieldOf("sector_front").forGetter(PersistenceState::sectorFront),
                        Codec.FLOAT.listOf().fieldOf("sector_energy").forGetter(PersistenceState::sectorEnergy),
                        Codec.DOUBLE.fieldOf("average_front").forGetter(PersistenceState::averageFront),
                        SurfaceState.CODEC.fieldOf("surface_state").forGetter(PersistenceState::surfaceState),
                        WorkState.CODEC.fieldOf("work_state").forGetter(PersistenceState::workState)
                ).apply(instance, PersistenceState::new)
        );

        private final BlockPos origin;
        private final double yieldKt;
        private final int ageTicks;
        private final boolean flashApplied;
        private final boolean craterFinished;
        private final boolean craterNeedsRebuild;
        private final List<Float> sectorFront;
        private final List<Float> sectorEnergy;
        private final double averageFront;
        private final SurfaceState surfaceState;
        private final WorkState workState;

        private PersistenceState(BlockPos origin, double yieldKt, int ageTicks, boolean flashApplied, boolean craterFinished,
                                 boolean craterNeedsRebuild, List<Float> sectorFront, List<Float> sectorEnergy, double averageFront,
                                 SurfaceState surfaceState, WorkState workState) {
            this.origin = origin;
            this.yieldKt = yieldKt;
            this.ageTicks = ageTicks;
            this.flashApplied = flashApplied;
            this.craterFinished = craterFinished;
            this.craterNeedsRebuild = craterNeedsRebuild;
            this.sectorFront = sectorFront;
            this.sectorEnergy = sectorEnergy;
            this.averageFront = averageFront;
            this.surfaceState = surfaceState;
            this.workState = workState;
        }

        private BlockPos origin() {
            return this.origin;
        }

        private double yieldKt() {
            return this.yieldKt;
        }

        private int ageTicks() {
            return this.ageTicks;
        }

        private boolean flashApplied() {
            return this.flashApplied;
        }

        private boolean craterFinished() {
            return this.craterFinished;
        }

        private boolean craterNeedsRebuild() {
            return this.craterNeedsRebuild;
        }

        private List<Float> sectorFront() {
            return this.sectorFront;
        }

        private List<Float> sectorEnergy() {
            return this.sectorEnergy;
        }

        private double averageFront() {
            return this.averageFront;
        }

        private SurfaceState surfaceState() {
            return this.surfaceState;
        }

        private WorkState workState() {
            return this.workState;
        }
    }

    private record SurfaceColumnSnapshot(int x, int z, int radial, double psi, int surfaceY, int floorY, BlockState[] states, double[] thresholds) {
        BlockState stateAt(int y) {
            int idx = this.surfaceY + 1 - y;
            return (idx >= 0 && idx < this.states.length) ? this.states[idx] : Blocks.AIR.defaultBlockState();
        }
        double thresholdAt(int y) {
            int idx = this.surfaceY + 1 - y;
            return (idx >= 0 && idx < this.thresholds.length) ? this.thresholds[idx] : Double.POSITIVE_INFINITY;
        }
    }

    private record StructureColumnSnapshot(int x, int z, int radial, double psi, double angle, int roofY, int minY, BlockState[] states, double[] exposures) {
        BlockState stateAt(int y) {
            int idx = this.roofY - y;
            return (idx >= 0 && idx < this.states.length) ? this.states[idx] : Blocks.AIR.defaultBlockState();
        }
        double exposureAt(int y) {
            int idx = this.roofY - y;
            return (idx >= 0 && idx < this.exposures.length) ? this.exposures[idx] : 1.0D;
        }
    }

    public ActiveNuclearBlast(BlockPos origin, Vec3 center, NuclearBlast.BlastGeometry geometry) {
        this(origin, center, geometry, new SurfaceHeightCache(),
                new float[BlastPhysicsConstants.shockSectors()],
                new float[BlastPhysicsConstants.shockSectors()],
                new float[BlastPhysicsConstants.shockSectors()]);
        for (int i = 0; i < this.sectorFront.length; i++) {
            this.sectorFront[i] = (float) geometry.fireballRadius();
            this.previousSectorFront[i] = (float) geometry.fireballRadius();
            this.sectorEnergy[i] = 1.0F;
        }
    }

    private ActiveNuclearBlast(BlockPos origin, Vec3 center, NuclearBlast.BlastGeometry geometry,
                               SurfaceHeightCache initialSurface, float[] sectorFront,
                               float[] previousSectorFront, float[] sectorEnergy) {
        this.origin = origin;
        this.center = center;
        this.geometry = geometry;
        this.initialSurface = initialSurface;
        this.sectorFront = sectorFront;
        this.previousSectorFront = previousSectorFront;
        this.sectorEnergy = sectorEnergy;
        this.averageFront = geometry.fireballRadius();
        this.previousAverageFront = this.averageFront;
        int craterRadius = Mth.ceil(geometry.craterRadius());
        this.craterCursorX = -craterRadius;
        this.craterCursorZ = -craterRadius;
    }

    PersistenceState toPersistenceState() {
        return new PersistenceState(
                this.origin,
                this.geometry.yieldKt(),
                this.ageTicks,
                this.flashApplied,
                this.craterFinished,
                !this.craterFinished && (!this.craterEditsPrepared || this.craterComputeFuture != null),
                toFloatList(this.sectorFront),
                toFloatList(this.sectorEnergy),
                this.averageFront,
                new SurfaceState(
                        this.initialSurface.snapshotEntries(),
                        toColumnPsiList(this.processedSurfaceColumns),
                        toColumnPsiList(this.processedStructureColumns)
                ),
                new WorkState(
                        new ArrayList<>(this.deferredEdits),
                        collectPendingShockTargets(),
                        collectReadyShockTargets(),
                        toBlockEditStates(this.craterEditQueue),
                        toBlockEditStates(this.shockEditQueue)
                )
        );
    }

    static ActiveNuclearBlast fromPersistenceState(PersistenceState state) {
        NuclearBlast.BlastGeometry geometry = NuclearBlast.BlastGeometry.fromYield(state.yieldKt());
        Vec3 center = Vec3.atCenterOf(state.origin());
        int sectorCount = Math.max(1, state.sectorFront().size());
        ActiveNuclearBlast blast = new ActiveNuclearBlast(
                state.origin().immutable(),
                center,
                geometry,
                new SurfaceHeightCache(),
                new float[sectorCount],
                new float[sectorCount],
                new float[sectorCount]
        );

        blast.ageTicks = state.ageTicks();
        blast.flashApplied = state.flashApplied();
        blast.craterFinished = state.craterFinished();
        blast.craterEditsPrepared = !state.craterNeedsRebuild();
        blast.averageFront = state.averageFront();
        blast.previousAverageFront = state.averageFront();
        blast.initialSurface.restoreEntries(state.surfaceState().surfaceHeights());
        copyFloats(state.sectorFront(), blast.sectorFront, (float) geometry.fireballRadius());
        System.arraycopy(blast.sectorFront, 0, blast.previousSectorFront, 0, blast.sectorFront.length);
        copyFloats(state.sectorEnergy(), blast.sectorEnergy, 1.0F);

        for (ColumnPsiEntry entry : state.surfaceState().processedSurfaceColumns()) {
            blast.processedSurfaceColumns.put(entry.column(), entry.psi());
        }
        for (ColumnPsiEntry entry : state.surfaceState().processedStructureColumns()) {
            blast.processedStructureColumns.put(entry.column(), entry.psi());
        }
        for (PendingEdit edit : state.workState().deferredEdits()) {
            blast.restoreDeferredEdit(edit);
        }
        for (ShockTarget target : state.workState().pendingShockTargets()) {
            blast.restorePendingShockTarget(target);
        }
        if (!state.workState().readyShockTargets().isEmpty()) {
            blast.readyShockTargets.add(new ArrayList<>(state.workState().readyShockTargets()));
        }
        for (BlockEditState editState : state.workState().craterEdits()) {
            blast.craterEditQueue.add(fromBlockEditState(editState));
        }
        for (BlockEditState editState : state.workState().shockEdits()) {
            blast.shockEditQueue.add(fromBlockEditState(editState));
        }
        return blast;
    }

    public boolean tick(ServerLevel level) {
        this.ageTicks++;
        if (!this.flashApplied) {
            this.flashApplied = true;
            applyInitialFlash(level);
        }

        if (!this.craterFinished) {
            processCrater(level);
            if (this.craterFinished && !this.craterCollapsed) {
                collapseCraterFloatingBlocks(level, Mth.ceil(this.geometry.craterRadius()));
                this.craterCollapsed = true;
            }
        }

        advanceShockwave(level);
        processShockwaveShell(level);
        if (this.ageTicks % 5 == 0) {
            damageEntities(level);
        }
        emitShellParticles(level);
        processDeferredEdits(level);

        if (this.craterFinished && allSectorsMaxed() && this.ageTicks > 20 && this.deferredEdits.isEmpty()) {
            return true;
        }
        int maxTicks = Mth.ceil(this.geometry.shockSurfaceRadius() / BlastPhysicsConstants.SOUND_SPEED_BLOCKS_PER_TICK) + 800;
        if (this.ageTicks > maxTicks) {
            this.deferredEdits.clear();
            return true;
        }
        return false;
    }

    private void applyInitialFlash(ServerLevel level) {
        double radius = this.geometry.thermalRadius();
        AABB bounds = new AABB(
                this.center.x - radius, this.center.y - radius * 0.3D, this.center.z - radius,
                this.center.x + radius, this.center.y + radius * 0.8D, this.center.z + radius
        );
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, bounds)) {
            double distance = entity.position().distanceTo(this.center);
            if (distance > radius) {
                continue;
            }
            if (!hasLineOfSight(level, this.center.add(0.0D, 0.8D, 0.0D), entity.getEyePosition())) {
                continue;
            }

            double exposure = 1.0D - Mth.clamp(distance / radius, 0.0D, 1.0D);
            double flashTemperature = this.geometry.maxTemperatureC() * exposure * exposure;
            if (flashTemperature >= 140.0D) {
                entity.igniteForSeconds((float) Mth.clamp(2.0D + exposure * 10.0D, 2.0D, 12.0D));
                entity.hurt(level.damageSources().onFire(), (float) (2.0D + exposure * 18.0D));
            }
            if (flashTemperature >= 400.0D) {
                entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0, true, false, true));
            }
            if (flashTemperature >= 260.0D) {
                entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 0, true, false, true));
            }
        }

        for (ServerPlayer player : level.players()) {
            double distance = player.position().distanceTo(this.center);
            if (distance <= this.geometry.thermalRadius() * 1.15D) {
                player.playSound(SoundEvents.GENERIC_EXPLODE.value(), 0.9F, 1.75F);
            }
        }
    }

    private void processCrater(ServerLevel level) {
        int craterRadius = Mth.ceil(this.geometry.craterRadius());
        int budget = craterMutationBudget();
        int maxScans = budget * 8;

        if (!this.craterEditsPrepared) {
            prepareCraterEditsAsync(level, craterRadius);
            this.craterEditsPrepared = true;
        }

        collectCompletedCraterEdits();

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int applied = 0;
        int scanned = 0;
        while (applied < budget && scanned < maxScans && !this.craterEditQueue.isEmpty()) {
            BlockEdit edit = this.craterEditQueue.remove(this.craterEditQueue.size() - 1);
            if (edit.apply(level, mutable)) {
                applied++;
            }
            scanned++;
        }

        this.craterFinished = this.craterEditsPrepared && this.craterComputeFuture == null && this.craterEditQueue.isEmpty();
    }

    private void collapseCraterFloatingBlocks(ServerLevel level, int craterRadius) {
        int maxFloor = Math.max(level.getMinY() + 6, this.origin.getY() - Mth.ceil(this.geometry.craterDepth()) - 4);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int maxFloorY = this.origin.getY();
        int budget = 3000;
        boolean changed = true;
        int passes = 0;
        while (changed && passes < 3 && budget > 0) {
            changed = false;
            passes++;
            for (int x = -craterRadius; x <= craterRadius && budget > 0; x++) {
                for (int z = -craterRadius; z <= craterRadius && budget > 0; z++) {
                    double distance = Math.sqrt(x * (double) x + z * (double) z);
                    if (distance > this.geometry.craterRadius()) {
                        continue;
                    }
                    int worldX = this.origin.getX() + x;
                    int worldZ = this.origin.getZ() + z;
                    if (!level.hasChunk(worldX >> 4, worldZ >> 4)) {
                        continue;
                    }
                    int initialY = this.initialSurface.getOrCapture(level, worldX, worldZ);
                    double normalized = distance / Math.max(1.0D, this.geometry.craterRadius());
                    double craterProfile = Math.pow(1.0D - normalized, 1.7D);
                    int columnDepth = Mth.floor(this.geometry.craterDepth() * craterProfile);
                    int floorY = Math.max(maxFloor, initialY - columnDepth);
                    // 从 floorY+1 往上扫描，如果方块悬空（下方是 air）则移除
                    for (int y = floorY + 1; y <= Math.min(maxFloorY + 12, initialY + 4) && budget > 0; y++) {
                        pos.set(worldX, y, worldZ);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir() || state.is(Blocks.BEDROCK) || state.hasBlockEntity()) {
                            continue;
                        }
                        BlockState below = level.getBlockState(pos.set(worldX, y - 1, worldZ));
                        if (below.isAir()) {
                            level.setBlock(pos.set(worldX, y, worldZ), Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS_ONLY);
                            budget--;
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    private void prepareCraterEditsAsync(ServerLevel level, int craterRadius) {
        int maxFloor = Math.max(level.getMinY() + 6, this.origin.getY() - Mth.ceil(this.geometry.craterDepth()) - 4);

        java.util.Set<Long> preloadedChunks = new java.util.HashSet<>();
        for (int x = -craterRadius; x <= craterRadius; x++) {
            for (int z = -craterRadius; z <= craterRadius; z++) {
                int worldX = this.origin.getX() + x;
                int worldZ = this.origin.getZ() + z;
                long chunkKey = ChunkPos.asLong(worldX >> 4, worldZ >> 4);
                if (preloadedChunks.add(chunkKey) && !level.hasChunk(worldX >> 4, worldZ >> 4)) {
                    level.getChunk(worldX >> 4, worldZ >> 4);
                }
                this.initialSurface.getOrCapture(level, worldX, worldZ);
            }
        }

        List<CraterColumnSnapshot> snapshots = new ArrayList<>();
        for (int x = -craterRadius; x <= craterRadius; x++) {
            int worldX = this.origin.getX() + x;
            for (int z = -craterRadius; z <= craterRadius; z++) {
                int worldZ = this.origin.getZ() + z;
                double distance = Math.sqrt(x * (double) x + z * (double) z);
                if (distance > this.geometry.craterRadius()) {
                    continue;
                }
                int initialY = this.initialSurface.getOrCapture(level, worldX, worldZ);
                double normalized = distance / Math.max(1.0D, this.geometry.craterRadius());
                double craterProfile = Math.pow(1.0D - normalized, 1.7D);
                int columnDepth = Mth.floor(this.geometry.craterDepth() * craterProfile);
                int floorY = Math.max(maxFloor, initialY - columnDepth);
                int fireballRoof = Mth.floor(this.origin.getY() + this.geometry.fireballRadius() * 0.28D * craterProfile);
                BlockState floorState = level.getBlockState(new BlockPos(worldX, floorY, worldZ));
                snapshots.add(new CraterColumnSnapshot(worldX, worldZ, initialY, floorY, fireballRoof, floorState));
            }
        }

        if (snapshots.isEmpty()) {
            return;
        }

        snapshots.sort((left, right) -> Double.compare(craterDistanceSq(right), craterDistanceSq(left)));
        this.craterComputeFuture = submitCraterEdits(snapshots);
    }

    private List<BlockEdit> computeCraterEdits(List<CraterColumnSnapshot> columns) {
        List<BlockEdit> edits = new ArrayList<>();
        for (CraterColumnSnapshot col : columns) {
            double dx = col.x - this.center.x;
            double dz = col.z - this.center.z;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance <= this.geometry.vitrificationRadius()) {
                BlockState replacement = chooseMeltedFloorFromState(col.floorState, col.x, col.floorY, col.z);
                edits.add(new BlockEdit(BlockPos.asLong(col.x, col.floorY, col.z), replacement, false));
            } else if (distance <= this.geometry.craterRadius() * 0.75D) {
                if (BlastMaterialRules.isScorchable(col.floorState)) {
                    edits.add(new BlockEdit(BlockPos.asLong(col.x, col.floorY, col.z), ModBlocks.SCORCHED_EARTH.get().defaultBlockState(), false));
                }
            }
            for (int y = col.floorY; y <= col.initialY; y++) {
                edits.add(new BlockEdit(BlockPos.asLong(col.x, y, col.z), null, true));
            }
            for (int y = col.initialY + 1; y <= col.fireballRoof; y++) {
                edits.add(new BlockEdit(BlockPos.asLong(col.x, y, col.z), null, true));
            }
        }
        return edits;
    }

    private BlockState chooseMeltedFloor(BlockPos pos, BlockState current) {
        return chooseMeltedFloorFromState(current, pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockState chooseMeltedFloorFromState(BlockState current, int x, int y, int z) {
        if (BlastMaterialRules.isVitrifiable(current)) {
            return ModBlocks.FUSED_GLASS.get().defaultBlockState();
        }
        if (BlastMaterialRules.isScorchable(current)) {
            return ModBlocks.SCORCHED_EARTH.get().defaultBlockState();
        }

        long hash = BlockPos.asLong(x, y, z) * 1103515245L + 12345L;
        int roll = Math.floorMod((int) (hash ^ (hash >>> 32)), 100);
        if (roll < 10) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (roll < 48) {
            return Blocks.BLACKSTONE.defaultBlockState();
        }
        if (roll < 82) {
            return Blocks.BASALT.defaultBlockState();
        }
        return Blocks.COARSE_DIRT.defaultBlockState();
    }

    private void advanceShockwave(ServerLevel level) {
        this.previousAverageFront = this.averageFront;
        double sum = 0.0D;
        float[] smoothed = new float[this.sectorFront.length];

        for (int i = 0; i < this.sectorFront.length; i++) {
            this.previousSectorFront[i] = this.sectorFront[i];
            float previous = this.sectorFront[i];
            double angle = sectorAngle(i);
            double baseAdvance = this.geometry.shockFrontSpeed(previous);
            float terrainFactor = terrainAdvanceFactor(level, angle, previous);
            this.sectorEnergy[i] = Mth.clamp(this.sectorEnergy[i] * (0.9985F - Math.max(0.0F, 1.0F - terrainFactor) * 0.0018F), 0.36F, 1.08F);
            float advanced = (float) Math.min(this.geometry.shockSurfaceRadius(), previous + baseAdvance * terrainFactor);
            smoothed[i] = advanced;
        }

        for (int i = 0; i < this.sectorFront.length; i++) {
            float left = smoothed[(i + this.sectorFront.length - 1) % this.sectorFront.length];
            float self = smoothed[i];
            float right = smoothed[(i + 1) % this.sectorFront.length];
            this.sectorFront[i] = left * 0.18F + self * 0.64F + right * 0.18F;
            sum += this.sectorFront[i];
        }

        this.averageFront = sum / this.sectorFront.length;
    }

    private float terrainAdvanceFactor(ServerLevel level, double angle, float front) {
        int x = Mth.floor(this.center.x + Math.cos(angle) * front);
        int z = Mth.floor(this.center.z + Math.sin(angle) * front);
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return 1.0F;
        }

        int sampleY = this.initialSurface.getOrCapture(level, x, z);

        float probeStep = (float) Math.max(8.0D, 10.0D + this.geometry.cubeRootScale() * 4.0D);
        float inwardFront = Math.max(0.0F, front - probeStep);
        float outwardFront = Math.min((float) this.geometry.shockSurfaceRadius(), front + probeStep);

        int inwardX = Mth.floor(this.center.x + Math.cos(angle) * inwardFront);
        int inwardZ = Mth.floor(this.center.z + Math.sin(angle) * inwardFront);
        int outwardX = Mth.floor(this.center.x + Math.cos(angle) * outwardFront);
        int outwardZ = Mth.floor(this.center.z + Math.sin(angle) * outwardFront);

        if (!level.hasChunk(inwardX >> 4, inwardZ >> 4) || !level.hasChunk(outwardX >> 4, outwardZ >> 4)) {
            return 1.0F;
        }

        int inwardY = this.initialSurface.getOrCapture(level, inwardX, inwardZ);
        int outwardY = this.initialSurface.getOrCapture(level, outwardX, outwardZ);

        float localRise = sampleY - inwardY;
        float localDrop = inwardY - sampleY;
        float ridgePenalty = Mth.clamp(localRise / (float) (12.0D + this.geometry.cubeRootScale() * 5.5D), 0.0F, 0.14F);
        float valleyBoost = Mth.clamp(localDrop / (float) (8.0D + this.geometry.cubeRootScale() * 3.6D), 0.0F, 0.18F);
        float cliffPenalty = Mth.clamp((sampleY - outwardY) / (float) (16.0D + this.geometry.cubeRootScale() * 5.0D), 0.0F, 0.04F);

        BlockState state = level.getBlockState(new BlockPos(x, sampleY, z));
        float waterBoost = state.getFluidState().is(FluidTags.WATER) ? 0.10F : 0.0F;
        float opennessBoost = level.canSeeSky(new BlockPos(x, sampleY + 1, z)) ? 0.05F : -0.01F;
        return Mth.clamp(1.0F - ridgePenalty - cliffPenalty + valleyBoost + waterBoost + opennessBoost, 0.80F, 1.24F);
    }

    private void processShockwaveShell(ServerLevel level) {
        collectCompletedShockBatches();
        applyShockEdits(level);
        processReadyShockTargets(level);

        int budget = BlastPhysicsConstants.shockBlockBudget();
        List<ShockTarget> targets = new ArrayList<>();

        for (int sector = 0; sector < this.sectorFront.length && budget > 0; sector++) {
            float previous = this.previousSectorFront[sector];
            float current = this.sectorFront[sector];
            if (current <= previous + 0.25F) {
                continue;
            }

            double angle = sectorAngle(sector);
            double perpX = -Math.sin(angle);
            double perpZ = Math.cos(angle);
            int stride = shellStride(current);
            double lateralSpacing = Math.max(1.5D, stride * 1.5D);
            for (int radial = Mth.floor(previous); radial <= Mth.floor(current) && budget > 0; radial += stride) {
                double shellPsi = this.geometry.peakOverpressurePsi(radial) * this.sectorEnergy[sector];
                if (shellPsi < 0.25D) {
                    continue;
                }

                int lateralSamples = BlastPhysicsConstants.lateralShellSamples(current) + extraLateralSamples(shellPsi);
                int wakeSteps = shockWakeSteps(shellPsi);
                for (int wakeStep = 0; wakeStep <= wakeSteps && budget > 0; wakeStep++) {
                    int sampleRadius = Math.max(0, radial - wakeStep * Math.max(1, stride));
                    double psi = shockWakePsi(shellPsi, wakeStep);
                    for (int lateralIndex = -lateralSamples; lateralIndex <= lateralSamples && budget > 0; lateralIndex++) {
                        double lateralOffset = lateralIndex * lateralSpacing;
                        int x = Mth.floor(this.center.x + Math.cos(angle) * sampleRadius + perpX * lateralOffset);
                        int z = Mth.floor(this.center.z + Math.sin(angle) * sampleRadius + perpZ * lateralOffset);
                        if (!level.hasChunk(x >> 4, z >> 4)) {
                            long key = BlockPos.asLong(x, 0, z);
                            if (this.deferredColumns.add(key)) {
                                deferredEdits.add(new PendingEdit(x, z, sampleRadius, psi, angle, false));
                                deferredEdits.add(new PendingEdit(x, z, sampleRadius, psi, angle, true));
                            }
                            continue;
                        }

                        budget -= 2;
                        long surfKey = BlockPos.asLong(x, 0, z);
                        long structKey = BlockPos.asLong(x, 1, z);
                        double prevSurfacePsi = this.processedSurfaceColumns.getOrDefault(surfKey, -1.0D);
                        double prevStructPsi = this.processedStructureColumns.getOrDefault(structKey, -1.0D);
                        boolean needsSurface = psi > prevSurfacePsi + 1.5D;
                        boolean needsStructural = psi > prevStructPsi + 1.5D;
                        if (needsSurface || needsStructural) {
                            targets.add(new ShockTarget(x, z, sampleRadius, psi, angle, needsSurface, needsStructural));
                        }
                    }
                }
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        List<SurfaceColumnSnapshot> surfaceSnaps = new ArrayList<>();
        List<StructureColumnSnapshot> structSnaps = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (ShockTarget t : targets) {
            if (t.needsSurface) {
                int surfaceY = this.initialSurface.getOrCapture(level, t.x, t.z);
                int floorY = Math.max(level.getMinY() + 4, surfaceY - shockScourDepth(t.psi, t.radial));
                int startY = surfaceY + 1;
                int endY = Math.max(level.getMinY() + 4, surfaceY - 6);
                if (endY > surfaceY) {
                    endY = surfaceY;
                }
                int len = Math.max(1, startY - endY + 1);
                BlockState[] states = new BlockState[len];
                double[] thresholds = new double[len];
                for (int y = startY, i = 0; y >= endY && i < len; y--, i++) {
                    m.set(t.x, y, t.z);
                    states[i] = level.getBlockState(m);
                    thresholds[i] = BlastMaterialRules.surfaceFailurePsi(level, m, states[i]);
                }
                surfaceSnaps.add(new SurfaceColumnSnapshot(t.x, t.z, t.radial, t.psi, surfaceY, floorY, states, thresholds));
            }
            if (t.needsStructural) {
                int roofY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, t.x, t.z) - 1;
                int minY = Math.max(level.getMinY() + 1, roofY - structureScanDepth(t.psi));
                if (roofY >= minY) {
                    int len = roofY - minY + 1;
                    BlockState[] states = new BlockState[len];
                    double[] exposures = new double[len];
                    double dirX = Math.cos(t.angle);
                    double dirZ = Math.sin(t.angle);
                    for (int y = roofY, i = 0; y >= minY; y--, i++) {
                        m.set(t.x, y, t.z);
                        states[i] = level.getBlockState(m);
                        exposures[i] = exposureFactor(level, m, dirX, dirZ, roofY);
                    }
                    structSnaps.add(new StructureColumnSnapshot(t.x, t.z, t.radial, t.psi, t.angle, roofY, minY, states, exposures));
                }
            }
        }

        this.pendingShockBatches.add(new PendingShockBatch(
                submitShockEdits(surfaceSnaps, structSnaps),
                new ArrayList<>(targets)
        ));
    }

    private void applyShockEdits(ServerLevel level) {
        int budget = BlastPhysicsConstants.shockBlockBudget();
        int maxScans = budget * 4;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int applied = 0;
        int scanned = 0;
        while (applied < budget && scanned < maxScans && !this.shockEditQueue.isEmpty()) {
            BlockEdit edit = this.shockEditQueue.remove(this.shockEditQueue.size() - 1);
            if (edit.apply(level, mutable)) {
                applied++;
            }
            scanned++;
        }
    }

    private int processSurfaceColumn(ServerLevel level, int x, int z, int radial, double psi) {
        long key = BlockPos.asLong(x, 0, z);
        double prevPsi = this.processedSurfaceColumns.getOrDefault(key, -1.0D);
        if (psi <= prevPsi + 1.5D) {
            return 0;
        }
        this.processedSurfaceColumns.put(key, psi);

        int initialY = this.initialSurface.getOrCapture(level, x, z);
        int scourDepth = shockScourDepth(psi, radial);
        int floorY = Math.max(level.getMinY() + 4, initialY - scourDepth);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, initialY, z);
        int edits = 0;

        BlockState topState = level.getBlockState(pos);
        boolean topExposed = isSurfaceExposed(level, pos);
        double reflectedSurfacePsi = surfaceShockPsi(psi, radial);

        if (psi >= 1.0D) {
            for (int y = initialY; y >= floorY; y--) {
                pos.set(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || state.is(Blocks.BEDROCK) || state.hasBlockEntity()) {
                    continue;
                }

                boolean exposed = y == initialY || isSurfaceExposed(level, pos);
                double localPsi = reflectedSurfacePsi * layerShockFactor(initialY - y, exposed);
                double threshold = BlastMaterialRules.surfaceFailurePsi(level, pos, state);

                if (BlastMaterialRules.isVitrifiable(state) && radial <= this.geometry.vitrificationRadius() * 1.2D && localPsi >= threshold * 0.85D) {
                    level.setBlock(pos, radial <= this.geometry.vitrificationRadius()
                            ? ModBlocks.FUSED_GLASS.get().defaultBlockState()
                            : Blocks.GLASS.defaultBlockState(), UPDATE_CLIENTS_ONLY);
                    edits++;
                    if (y < initialY) {
                        break;
                    }
                    continue;
                }

                if (localPsi >= threshold) {
                    if (BlastMaterialRules.isScorchable(state) && y == initialY && localPsi < threshold * 1.45D) {
                        level.setBlock(pos, ModBlocks.SCORCHED_EARTH.get().defaultBlockState(), UPDATE_CLIENTS_ONLY);
                    } else {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS_ONLY);
                    }
                    edits++;
                    if (!BlastMaterialRules.isLooseSurface(state) && !BlastMaterialRules.isScorchable(state) && !isRockySurface(state)) {
                        break;
                    }
                    continue;
                }

                if (exposed && localPsi >= threshold * 0.75D && isRockySurface(state)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS_ONLY);
                    edits++;
                    break;
                }
            }
        }

        if (edits == 0 && BlastMaterialRules.isScorchable(topState) && psi >= 0.8D) {
            level.setBlock(pos, ModBlocks.SCORCHED_EARTH.get().defaultBlockState(), UPDATE_CLIENTS_ONLY);
            edits++;
        }

        if (psi >= 3.0D && topState.getFluidState().is(FluidTags.WATER)) {
            BlockPos waterSeed = WaterEvaporationUtil.findWaterSeed(level, x, z, 24);
            if (waterSeed != null) {
                flashBoilWater(level, waterSeed, psi);
                edits++;
            }
        }

        if (radial <= this.geometry.scorchRadius() && psi >= 0.6D) {
            maybeIgniteSurface(level, x, initialY, z);
        }

        if (topExposed && psi >= 1.2D) {
            edits += disturbSurfaceMargins(level, x, z, psi, radial);
        }

        return edits;
    }

    private int disturbSurfaceMargins(ServerLevel level, int x, int z, double psi, int radial) {
        int edits = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double reflectedSurfacePsi = surfaceShockPsi(psi, radial) * 0.82D;
        for (Direction direction : HORIZONTAL) {
            int nx = x + direction.getStepX();
            int nz = z + direction.getStepZ();
            int ny = this.initialSurface.getOrCapture(level, nx, nz);
            pos.set(nx, ny, nz);
            BlockState neighbor = level.getBlockState(pos);
            if (neighbor.isAir() || neighbor.hasBlockEntity()) {
                continue;
            }

            double threshold = BlastMaterialRules.surfaceFailurePsi(level, pos, neighbor);
            if (reflectedSurfacePsi >= threshold) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS_ONLY);
                edits++;
                continue;
            }

            if (BlastMaterialRules.isScorchable(neighbor) && reflectedSurfacePsi >= threshold * 0.7D) {
                level.setBlock(pos, ModBlocks.SCORCHED_EARTH.get().defaultBlockState(), UPDATE_CLIENTS_ONLY);
                edits++;
                continue;
            }

            if (BlastMaterialRules.isVitrifiable(neighbor) && radial <= this.geometry.vitrificationRadius() * 1.35D && reflectedSurfacePsi >= threshold * 0.8D) {
                level.setBlock(pos, ModBlocks.FUSED_GLASS.get().defaultBlockState(), UPDATE_CLIENTS_ONLY);
                edits++;
            }
        }
        return edits;
    }

    private int processStructureColumn(ServerLevel level, int x, int z, double angle, int radial, double psi) {
        long key = BlockPos.asLong(x, 1, z);
        double prevPsi = this.processedStructureColumns.getOrDefault(key, -1.0D);
        if (psi <= prevPsi + 1.5D) {
            return 0;
        }
        this.processedStructureColumns.put(key, psi);

        int roofY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int edits = 0;
        int minY = Math.max(level.getMinY() + 1, roofY - structureScanDepth(psi));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double dirX = Math.cos(angle);
        double dirZ = Math.sin(angle);

        for (int y = roofY; y >= minY; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.hasBlockEntity()) {
                continue;
            }

            if (!isStructuralTarget(level, pos, roofY)) {
                continue;
            }

            double requiredPsi = BlastMaterialRules.blastResistancePsi(state);
            double exposure = exposureFactor(level, pos, dirX, dirZ, roofY);
            double effectivePsi = structureShockPsi(psi, radial) * exposure;
            if (BlastMaterialRules.isVegetationOrLightStructure(state)) {
                effectivePsi *= 1.55D;
                requiredPsi *= 0.74D;
            }
            if (effectivePsi < requiredPsi) {
                continue;
            }

            if (state.is(BlockTags.FIRE) || state.is(Blocks.WATER)) {
                continue;
            }

            if (state.getFluidState().is(FluidTags.WATER)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS_ONLY);
            } else {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_CLIENTS_ONLY);
            }
            edits++;

            if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x + 0.5D, y + 0.5D, z + 0.5D, 8, 0.22D, 0.22D, 0.22D, 0.04D);
            }

            if (edits >= maxStructureEdits(psi)) {
                break;
            }
        }

        return edits;
    }

    private List<BlockEdit> computeSurfaceEdits(SurfaceColumnSnapshot snap) {
        List<BlockEdit> edits = new ArrayList<>();
        BlockState topState = snap.stateAt(snap.surfaceY);
        boolean topExposed = snap.states[0].isAir();
        double reflectedSurfacePsi = surfaceShockPsi(snap.psi, snap.radial);

        if (snap.psi >= 1.0D) {
            for (int y = snap.surfaceY; y >= snap.floorY; y--) {
                BlockState state = snap.stateAt(y);
                if (state.isAir() || state.is(Blocks.BEDROCK) || state.hasBlockEntity()) {
                    continue;
                }

                boolean exposed = y == snap.surfaceY || (snap.stateAt(y + 1).isAir());
                double localPsi = reflectedSurfacePsi * layerShockFactor(snap.surfaceY - y, exposed);
                double threshold = snap.thresholdAt(y);

                if (BlastMaterialRules.isVitrifiable(state) && snap.radial <= this.geometry.vitrificationRadius() * 1.2D && localPsi >= threshold * 0.85D) {
                    edits.add(new BlockEdit(BlockPos.asLong(snap.x, y, snap.z), snap.radial <= this.geometry.vitrificationRadius()
                            ? ModBlocks.FUSED_GLASS.get().defaultBlockState()
                            : Blocks.GLASS.defaultBlockState(), false));
                    if (y < snap.surfaceY) {
                        break;
                    }
                    continue;
                }

                if (localPsi >= threshold) {
                    if (BlastMaterialRules.isScorchable(state) && y == snap.surfaceY && localPsi < threshold * 1.45D) {
                        edits.add(new BlockEdit(BlockPos.asLong(snap.x, y, snap.z), ModBlocks.SCORCHED_EARTH.get().defaultBlockState(), false));
                    } else {
                        edits.add(new BlockEdit(BlockPos.asLong(snap.x, y, snap.z), null, true));
                    }
                    if (!BlastMaterialRules.isLooseSurface(state) && !BlastMaterialRules.isScorchable(state) && !isRockySurface(state)) {
                        break;
                    }
                    continue;
                }

                if (exposed && localPsi >= threshold * 0.75D && isRockySurface(state)) {
                    edits.add(new BlockEdit(BlockPos.asLong(snap.x, y, snap.z), null, true));
                    break;
                }
            }
        }

        if (edits.isEmpty() && BlastMaterialRules.isScorchable(topState) && snap.psi >= 0.8D) {
            edits.add(new BlockEdit(BlockPos.asLong(snap.x, snap.surfaceY, snap.z), ModBlocks.SCORCHED_EARTH.get().defaultBlockState(), false));
        }

        return edits;
    }

    private List<BlockEdit> computeStructureEdits(StructureColumnSnapshot snap) {
        List<BlockEdit> edits = new ArrayList<>();
        double dirX = Math.cos(snap.angle);
        double dirZ = Math.sin(snap.angle);

        for (int y = snap.roofY; y >= snap.minY; y--) {
            BlockState state = snap.stateAt(y);
            if (state.isAir() || state.hasBlockEntity()) {
                continue;
            }

            if (!isStructuralTargetFromSnapshot(state, y, snap.roofY)) {
                continue;
            }

            double requiredPsi = BlastMaterialRules.blastResistancePsi(state);
            double exposure = snap.exposureAt(y);
            double effectivePsi = structureShockPsi(snap.psi, snap.radial) * exposure;
            if (BlastMaterialRules.isVegetationOrLightStructure(state)) {
                effectivePsi *= 1.55D;
                requiredPsi *= 0.74D;
            }
            if (effectivePsi < requiredPsi) {
                continue;
            }

            if (state.is(BlockTags.FIRE) || state.is(Blocks.WATER)) {
                continue;
            }

            edits.add(new BlockEdit(BlockPos.asLong(snap.x, y, snap.z), null, true));

            if (edits.size() >= maxStructureEdits(snap.psi)) {
                break;
            }
        }

        return edits;
    }

    private static boolean isStructuralTargetFromSnapshot(BlockState state, int y, int roofY) {
        if (BlastMaterialRules.isRoofLike(state) || BlastMaterialRules.isVegetationOrLightStructure(state)
                || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) {
            return true;
        }
        return y >= roofY - 1;
    }

    private boolean isStructuralTarget(ServerLevel level, BlockPos pos, int roofY) {
        BlockState state = level.getBlockState(pos);
        if (BlastMaterialRules.isRoofLike(state) || BlastMaterialRules.isVegetationOrLightStructure(state)
                || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) {
            return true;
        }

        int airNeighbors = 0;
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                airNeighbors++;
            }
        }
        return airNeighbors >= 2 || pos.getY() >= roofY - 1;
    }

    private boolean isSurfaceExposed(ServerLevel level, BlockPos pos) {
        if (level.isEmptyBlock(pos.above())) {
            return true;
        }
        int openSides = 0;
        for (Direction direction : HORIZONTAL) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                openSides++;
            }
        }
        return openSides >= 2;
    }

    private double exposureFactor(ServerLevel level, BlockPos pos, double dirX, double dirZ, int roofY) {
        double factor = 1.0D;
        BlockState state = level.getBlockState(pos);
        if (BlastMaterialRules.isRoofLike(state)) {
            factor *= 1.24D;
        }
        if (pos.getY() > roofY - 2) {
            factor *= 1.15D;
        }
        if (pos.getY() < roofY - 8) {
            factor *= 0.62D;
        }

        int blockedFaces = 0;
        for (Direction direction : HORIZONTAL) {
            if (!level.getBlockState(pos.relative(direction)).isAir()) {
                blockedFaces++;
            }
        }
        if (blockedFaces >= 3) {
            factor *= 0.76D;
        }

        Vec3 from = this.center.add(0.0D, 1.2D, 0.0D);
        Vec3 to = Vec3.atCenterOf(pos);
        if (BlastPhysicsConstants.useBlockLineOfSight()) {
            if (!hasLineOfSight(level, from, to)) {
                factor *= 0.72D;
            }
        } else if (!level.canSeeSky(pos.above())) {
            factor *= 0.92D;
        }

        double facing = dirX * pos.getX() + dirZ * pos.getZ();
        if (facing > dirX * this.center.x + dirZ * this.center.z) {
            factor *= 1.04D;
        }
        return factor;
    }

    private int structureScanDepth(double psi) {
        if (psi >= 15.0D) {
            return 48;
        }
        if (psi >= 8.0D) {
            return 36;
        }
        if (psi >= 4.0D) {
            return 26;
        }
        if (psi >= 2.0D) {
            return 18;
        }
        return 12;
    }

    private int shockScourDepth(double psi, int radial) {
        int depth = 0;
        if (psi >= 16.0D) {
            depth = 5;
        } else if (psi >= 12.0D) {
            depth = 4;
        } else if (psi >= 8.0D) {
            depth = 3;
        } else if (psi >= 4.0D) {
            depth = 2;
        } else if (psi >= 1.5D) {
            depth = 1;
        }

        if (radial <= this.geometry.shockCoreRadius() * 1.1D && psi >= 5.0D) {
            depth += 1;
        }
        return Math.min(6, depth);
    }

    private boolean isRockySurface(BlockState state) {
        return BlastMaterialRules.isRockLike(state) || state.is(Blocks.DEEPSLATE);
    }

    private double surfaceShockPsi(double psi, int radial) {
        double nearBoost = 2.00D;
        if (radial <= this.geometry.shockCoreRadius()) {
            nearBoost = 2.55D;
        } else if (radial <= this.geometry.shockSevereRadius()) {
            nearBoost = 2.25D;
        }
        return psi * nearBoost;
    }

    private double layerShockFactor(int depthBelowSurface, boolean exposed) {
        double factor = exposed ? 1.0D : 0.90D;
        return Math.max(0.58D, factor - depthBelowSurface * 0.12D);
    }

    private double structureShockPsi(double psi, int radial) {
        double boost = 1.82D;
        if (radial <= this.geometry.shockCoreRadius()) {
            boost = 2.45D;
        } else if (radial <= this.geometry.shockSevereRadius()) {
            boost = 2.05D;
        }
        return psi * boost;
    }

    private int extraLateralSamples(double psi) {
        if (psi >= 10.0D) {
            return 2;
        }
        if (psi >= 3.0D) {
            return 1;
        }
        return 0;
    }

    private int shockWakeSteps(double psi) {
        if (psi >= 10.0D) {
            return 3;
        }
        if (psi >= 6.0D) {
            return 2;
        }
        if (psi >= 2.0D) {
            return 1;
        }
        return 0;
    }

    private double shockWakePsi(double shellPsi, int wakeStep) {
        if (wakeStep <= 0) {
            return shellPsi;
        }
        return switch (wakeStep) {
            case 1 -> shellPsi * 0.86D;
            case 2 -> shellPsi * 0.70D;
            default -> shellPsi * 0.54D;
        };
    }

    private int maxStructureEdits(double psi) {
        if (psi >= 15.0D) {
            return 64;
        }
        if (psi >= 8.0D) {
            return 48;
        }
        if (psi >= 4.0D) {
            return 32;
        }
        if (psi >= 2.0D) {
            return 20;
        }
        return 14;
    }

    private void damageEntities(ServerLevel level) {
        double min = this.previousAverageFront - shellWidth(this.previousAverageFront);
        double max = this.averageFront + shellWidth(this.averageFront);
        AABB bounds = new AABB(
                this.center.x - max, this.center.y - 24.0D, this.center.z - max,
                this.center.x + max, this.center.y + 64.0D, this.center.z + max
        );

        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, bounds)) {
            double distance = entity.position().distanceTo(this.center);
            if (distance < min || distance > max) {
                continue;
            }
            double psi = this.geometry.peakOverpressurePsi(distance);
            if (psi < 0.25D) {
                continue;
            }

            double attenuation = hasLineOfSight(level, this.center.add(0.0D, 1.2D, 0.0D), entity.getEyePosition()) ? 1.0D : 0.55D;
            double effectivePsi = psi * attenuation;
            float damage = (float) Math.min(32.0D, effectivePsi * 1.5D);
            if (damage > 0.5F) {
                entity.hurt(level.damageSources().explosion(null), damage);
            }

            Vec3 push = entity.position().subtract(this.center).normalize().scale(Math.min(3.5D, 0.12D + effectivePsi * 0.08D));
            entity.push(push.x, 0.15D + Math.min(0.9D, effectivePsi * 0.03D), push.z);
            entity.hurtMarked = true;

            if (effectivePsi >= 4.0D) {
                entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0, true, false, true));
            }

            if (entity instanceof ServerPlayer player) {
                playShockArrivalForPlayer(player, effectivePsi);
            }
        }
    }

    private void playShockArrivalForPlayer(ServerPlayer player, double effectivePsi) {
        if (!this.heardShockPlayers.add(player.getUUID())) {
            return;
        }

        float volume = (float) Mth.clamp(0.8D + effectivePsi * 0.12D, 0.8D, 3.2D);
        float pitch = (float) Mth.clamp(0.55D - effectivePsi * 0.01D, 0.38D, 0.65D);
        player.playSound(SoundEvents.GENERIC_EXPLODE.value(), volume, pitch);
    }

    private void flashBoilWater(ServerLevel level, BlockPos source, double psi) {
        WaterEvaporationUtil.evaporateAround(
                level,
                source,
                4 + Mth.floor((float) Math.min(8.0D, psi)),
                18 + Mth.floor((float) Math.min(280.0D, psi * 20.0D)),
                true
        );
    }

    private void maybeIgniteSurface(ServerLevel level, int x, int surfaceY, int z) {
        BlockPos ground = new BlockPos(x, surfaceY, z);
        BlockState groundState = level.getBlockState(ground);
        if (!BlastMaterialRules.isFlammable(groundState)) {
            return;
        }
        BlockPos above = ground.above();
        if (level.isEmptyBlock(above)) {
            level.setBlock(above, BaseFireBlock.getState(level, above), UPDATE_CLIENTS_ONLY);
        }
    }

    private void emitShellParticles(ServerLevel level) {
        int particleCount = 8 + Mth.floor((float) (this.geometry.cubeRootScale() * 4.0D));
        for (int i = 0; i < particleCount; i++) {
            int sector = (this.ageTicks * 7 + i * 13) % this.sectorFront.length;
            double angle = sectorAngle(sector);
            double radius = this.sectorFront[sector];
            double x = this.center.x + Math.cos(angle) * radius;
            double z = this.center.z + Math.sin(angle) * radius;
            int ix = Mth.floor(x);
            int iz = Mth.floor(z);
            if (!level.hasChunk(ix >> 4, iz >> 4)) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ix, iz) - 1;
            level.sendParticles(ParticleTypes.CLOUD, x, y + 0.35D, z, 1, 0.20D, 0.05D, 0.20D, 0.01D);
            if (i % 2 == 0) {
                level.sendParticles(ParticleTypes.ASH, x, y + 0.6D, z, 1, 0.16D, 0.12D, 0.16D, 0.0D);
            }
        }

    }

    private boolean hasLineOfSight(ServerLevel level, Vec3 from, Vec3 to) {
        HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS;
    }

    private void collectCompletedCraterEdits() {
        if (this.craterComputeFuture == null || !this.craterComputeFuture.isDone()) {
            return;
        }
        try {
            this.craterEditQueue.addAll(this.craterComputeFuture.join());
        } catch (CompletionException ex) {
            AtomfallMod.LOGGER.error("Atomfall crater compute failed", ex.getCause() != null ? ex.getCause() : ex);
        } finally {
            this.craterComputeFuture = null;
        }
    }

    private void collectCompletedShockBatches() {
        java.util.Iterator<PendingShockBatch> iterator = this.pendingShockBatches.iterator();
        while (iterator.hasNext()) {
            PendingShockBatch batch = iterator.next();
            if (!batch.editsFuture().isDone()) {
                continue;
            }
            try {
                this.shockEditQueue.addAll(batch.editsFuture().join());
                this.readyShockTargets.add(batch.targets());
            } catch (CompletionException ex) {
                AtomfallMod.LOGGER.error("Atomfall shock compute failed", ex.getCause() != null ? ex.getCause() : ex);
            } finally {
                iterator.remove();
            }
        }
    }

    private void processReadyShockTargets(ServerLevel level) {
        if (this.readyShockTargets.isEmpty()) {
            return;
        }
        int budget = BlastPhysicsConstants.shockBlockBudget();
        outer:
        for (List<ShockTarget> targets : this.readyShockTargets) {
            for (ShockTarget target : targets) {
                if (budget <= 0) {
                    break outer;
                }
                if (!target.needsSurface) {
                    continue;
                }
                int surfaceY = this.initialSurface.getOrCapture(level, target.x, target.z);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(target.x, surfaceY, target.z);
                if (target.psi >= 3.0D && level.getBlockState(pos).getFluidState().is(FluidTags.WATER)) {
                    BlockPos waterSeed = WaterEvaporationUtil.findWaterSeed(level, target.x, target.z, 24);
                    if (waterSeed != null) {
                        flashBoilWater(level, waterSeed, target.psi);
                        budget -= 4;
                    }
                }
                if (target.radial <= this.geometry.scorchRadius() && target.psi >= 0.6D) {
                    maybeIgniteSurface(level, target.x, surfaceY, target.z);
                    budget -= 1;
                }
                if (isSurfaceExposed(level, pos) && target.psi >= 1.2D) {
                    budget -= disturbSurfaceMargins(level, target.x, target.z, target.psi, target.radial);
                }
            }
        }
        this.readyShockTargets.clear();
    }

    private void processDeferredEdits(ServerLevel level) {
        if (this.deferredEdits.isEmpty()) {
            return;
        }
        int budget = BlastPhysicsConstants.shockBlockBudget() / 3;
        java.util.Iterator<PendingEdit> iterator = this.deferredEdits.iterator();
        while (iterator.hasNext() && budget > 0) {
            PendingEdit edit = iterator.next();
            BlockPos columnPos = new BlockPos(edit.x, this.origin.getY(), edit.z);
            if (!level.hasChunkAt(columnPos)) {
                continue;
            }
            if (edit.structural) {
                budget -= processStructureColumn(level, edit.x, edit.z, edit.angle, edit.radial, edit.psi);
            } else {
                budget -= processSurfaceColumn(level, edit.x, edit.z, edit.radial, edit.psi);
            }
            iterator.remove();
            this.deferredColumns.remove(BlockPos.asLong(edit.x, 0, edit.z));
        }
        if (this.deferredEdits.size() > 50000) {
            int removeCount = this.deferredEdits.size() - 40000;
            for (int i = 0; i < removeCount; i++) {
                PendingEdit edit = this.deferredEdits.get(i);
                this.deferredColumns.remove(BlockPos.asLong(edit.x, 0, edit.z));
            }
            this.deferredEdits.subList(0, removeCount).clear();
        }
    }

    private int shellStride(double radius) {
        if (radius <= 180.0D) {
            return BlastPhysicsConstants.shockSampleStrideNear();
        }
        if (radius <= 700.0D) {
            return BlastPhysicsConstants.shockSampleStrideMid();
        }
        return BlastPhysicsConstants.shockSampleStrideFar();
    }

    private double shellWidth(double radius) {
        return Math.max(6.0D, this.geometry.shockFrontSpeed(radius) * 1.35D);
    }

    private double sectorAngle(int sector) {
        return sector * (Math.PI * 2.0D / this.sectorFront.length);
    }

    private boolean allSectorsMaxed() {
        for (float front : this.sectorFront) {
            if (front < (float) this.geometry.shockSurfaceRadius() - 0.5F) {
                return false;
            }
        }
        return true;
    }

    private int craterMutationBudget() {
        int baseBudget = BlastPhysicsConstants.craterBlockBudget();
        if (this.ageTicks <= 5) {
            return baseBudget * 6;
        }
        if (this.ageTicks <= 20) {
            return baseBudget * 3;
        }
        return baseBudget;
    }

    private double craterDistanceSq(CraterColumnSnapshot snapshot) {
        double dx = snapshot.x - this.center.x;
        double dz = snapshot.z - this.center.z;
        return dx * dx + dz * dz;
    }

    private CompletableFuture<List<BlockEdit>> submitCraterEdits(List<CraterColumnSnapshot> snapshots) {
        int processors = Runtime.getRuntime().availableProcessors();
        if (processors < 2 || snapshots.size() < PARALLEL_MIN_SNAPSHOTS) {
            return CompletableFuture.supplyAsync(() -> computeCraterEdits(snapshots), COMPUTE_EXECUTOR);
        }

        int stripCount = Math.min(processors, snapshots.size());
        int stripSize = (snapshots.size() + stripCount - 1) / stripCount;
        List<CompletableFuture<List<BlockEdit>>> futures = new ArrayList<>();
        for (int stripIndex = 0; stripIndex < stripCount; stripIndex++) {
            int from = stripIndex * stripSize;
            int to = Math.min(from + stripSize, snapshots.size());
            if (from >= to) {
                break;
            }
            List<CraterColumnSnapshot> strip = snapshots.subList(from, to);
            futures.add(CompletableFuture.supplyAsync(() -> computeCraterEdits(strip), COMPUTE_EXECUTOR));
        }
        return combineEditFutures(futures);
    }

    private CompletableFuture<List<BlockEdit>> submitShockEdits(List<SurfaceColumnSnapshot> surfaceSnaps, List<StructureColumnSnapshot> structSnaps) {
        int processors = Runtime.getRuntime().availableProcessors();
        if (processors < 2 || surfaceSnaps.size() + structSnaps.size() < PARALLEL_MIN_SNAPSHOTS) {
            return CompletableFuture.supplyAsync(() -> {
                List<BlockEdit> edits = new ArrayList<>();
                for (SurfaceColumnSnapshot snap : surfaceSnaps) {
                    edits.addAll(computeSurfaceEdits(snap));
                }
                for (StructureColumnSnapshot snap : structSnaps) {
                    edits.addAll(computeStructureEdits(snap));
                }
                return edits;
            }, COMPUTE_EXECUTOR);
        }

        List<CompletableFuture<List<BlockEdit>>> futures = new ArrayList<>();
        submitSurfaceEditStrips(surfaceSnaps, processors, futures);
        submitStructureEditStrips(structSnaps, processors, futures);
        return combineEditFutures(futures);
    }

    private void submitSurfaceEditStrips(List<SurfaceColumnSnapshot> surfaceSnaps, int processors, List<CompletableFuture<List<BlockEdit>>> futures) {
        int stripCount = Math.min(processors, surfaceSnaps.size());
        if (stripCount <= 0) {
            return;
        }
        int stripSize = (surfaceSnaps.size() + stripCount - 1) / stripCount;
        for (int stripIndex = 0; stripIndex < stripCount; stripIndex++) {
            int from = stripIndex * stripSize;
            int to = Math.min(from + stripSize, surfaceSnaps.size());
            if (from >= to) {
                break;
            }
            List<SurfaceColumnSnapshot> strip = surfaceSnaps.subList(from, to);
            futures.add(CompletableFuture.supplyAsync(() -> {
                List<BlockEdit> edits = new ArrayList<>();
                for (SurfaceColumnSnapshot snap : strip) {
                    edits.addAll(computeSurfaceEdits(snap));
                }
                return edits;
            }, COMPUTE_EXECUTOR));
        }
    }

    private void submitStructureEditStrips(List<StructureColumnSnapshot> structSnaps, int processors, List<CompletableFuture<List<BlockEdit>>> futures) {
        int stripCount = Math.min(processors, structSnaps.size());
        if (stripCount <= 0) {
            return;
        }
        int stripSize = (structSnaps.size() + stripCount - 1) / stripCount;
        for (int stripIndex = 0; stripIndex < stripCount; stripIndex++) {
            int from = stripIndex * stripSize;
            int to = Math.min(from + stripSize, structSnaps.size());
            if (from >= to) {
                break;
            }
            List<StructureColumnSnapshot> strip = structSnaps.subList(from, to);
            futures.add(CompletableFuture.supplyAsync(() -> {
                List<BlockEdit> edits = new ArrayList<>();
                for (StructureColumnSnapshot snap : strip) {
                    edits.addAll(computeStructureEdits(snap));
                }
                return edits;
            }, COMPUTE_EXECUTOR));
        }
    }

    private static CompletableFuture<List<BlockEdit>> combineEditFutures(List<CompletableFuture<List<BlockEdit>>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<BlockEdit> edits = new ArrayList<>();
                    for (CompletableFuture<List<BlockEdit>> future : futures) {
                        edits.addAll(future.join());
                    }
                    return edits;
                });
    }

    private List<ShockTarget> collectPendingShockTargets() {
        List<ShockTarget> targets = new ArrayList<>();
        for (PendingShockBatch batch : this.pendingShockBatches) {
            targets.addAll(batch.targets());
        }
        return targets;
    }

    private List<ShockTarget> collectReadyShockTargets() {
        List<ShockTarget> targets = new ArrayList<>();
        for (List<ShockTarget> batch : this.readyShockTargets) {
            targets.addAll(batch);
        }
        return targets;
    }

    private void restoreDeferredEdit(PendingEdit edit) {
        this.deferredEdits.add(edit);
        this.deferredColumns.add(BlockPos.asLong(edit.x(), 0, edit.z()));
        clearProcessedFlag(edit);
    }

    private void restorePendingShockTarget(ShockTarget target) {
        if (target.needsSurface()) {
            restoreDeferredEdit(new PendingEdit(target.x(), target.z(), target.radial(), target.psi(), target.angle(), false));
        }
        if (target.needsStructural()) {
            restoreDeferredEdit(new PendingEdit(target.x(), target.z(), target.radial(), target.psi(), target.angle(), true));
        }
    }

    private void clearProcessedFlag(PendingEdit edit) {
        long key = BlockPos.asLong(edit.x(), edit.structural() ? 1 : 0, edit.z());
        if (edit.structural()) {
            this.processedStructureColumns.put(key, -1.0D);
        } else {
            this.processedSurfaceColumns.put(key, -1.0D);
        }
    }

    private static List<Float> toFloatList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add(value);
        }
        return list;
    }

    private static void copyFloats(List<Float> source, float[] target, float defaultValue) {
        Arrays.fill(target, defaultValue);
        for (int i = 0; i < target.length && i < source.size(); i++) {
            target[i] = source.get(i);
        }
    }

    private static List<ColumnPsiEntry> toColumnPsiList(it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap values) {
        List<ColumnPsiEntry> list = new ArrayList<>(values.size());
        for (it.unimi.dsi.fastutil.longs.Long2DoubleMap.Entry entry : values.long2DoubleEntrySet()) {
            list.add(new ColumnPsiEntry(entry.getLongKey(), entry.getDoubleValue()));
        }
        return list;
    }

    private static List<BlockEditState> toBlockEditStates(List<BlockEdit> edits) {
        List<BlockEditState> states = new ArrayList<>(edits.size());
        for (BlockEdit edit : edits) {
            states.add(new BlockEditState(edit.packedPos(), edit.remove(), edit.newState()));
        }
        return states;
    }

    private static BlockEdit fromBlockEditState(BlockEditState state) {
        return new BlockEdit(state.packedPos(), state.newState(), state.remove());
    }

    private static ExecutorService createComputeExecutor() {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        AtomicInteger nextId = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "atomfall-blast-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(threads, threadFactory);
    }
}
