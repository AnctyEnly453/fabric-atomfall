# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Atomfall is a **Fabric mod for Minecraft 1.21.11** that adds nuclear weapons, radiation, and post-blast environmental effects. The mod features cinematic explosions, radiation contamination zones, temperature systems, and hazmat equipment.

## Key Commands

```bash
# Build the mod
./gradlew build

# Run the game with the mod (development client)
./gradlew runClient

# Run the dedicated server
./gradlew runServer

# Generate decompiled sources for IDE debugging
./gradlew genSources

# Publish (local)
./gradlew publishToMavenLocal

# Clean build artifacts
./gradlew clean
```

- Java 21 is required (`--release 21`).
- The project uses **Mojang's official mappings** (not Yarn).

## Architecture

### Split Source Sets

The mod uses Fabric Loom's `splitEnvironmentSourceSets`, separating **server/common** and **client** code:

- `src/main/java/...` — Game logic, blocks, entities, world systems (runs on both sides)
- `src/client/java/...` — Rendering, models, overlays, client-side mixins (runs on client only)

### Core Packages

**`com.codex.atomfall`**
- `AtomfallMod` — Main entry point. Initializes all registries and events.
- `AtomfallEvents` — Server-side event hooks: world tick, radiation/temperature systems, player lifecycle events.
- `AtomfallCommands` — Command registration (`/atomfall performance`, `/atomfall thermal`, `/atomfall heatwave`).

**`common/block/`** — `AtomicBombBlock`, `ScorchedEarthBlock`, and block entities. Bomb blocks tick toward detonation when activated.

**`common/entity/`** — Dynamic visual effects spawned during a blast:
- `NuclearCloudEntity` — The rising mushroom cloud with particle generation.
- `ShockwaveRingEntity` — Expanding blast wave visual ring.
- `ThermalPulseRingEntity` — Heat/flash wave visual ring.

**`common/world/`** — Blast physics and world modification logic:
- `NuclearBlast` — Main detonation orchestrator. Spawns visual entities, creates persistent radiation/temperature zones, scorches terrain.
  - `BlastGeometry` — Record derived from yield that computes all radii (fireball, crater, shock, thermal, radiation).
- `ActiveNuclearBlast` — Per-detonation state machine. **Supports save/reload persistence** (`PersistenceState`). Heavy work is split into:
  1. Crater excavation (async pre-compute + queue-based application + floating-block collapse)
  2. Shockwave shell sampling with sector fronts
  3. Surface/structure column edits around the active shell
  4. **Chunk-safe**: `terrainAdvanceFactor` skips unloaded chunks; `SurfaceHeightCache` returns `seaLevel` for unloaded chunks.
- `ActiveBlastSavedData` — Extends `SavedData`, persists active blasts to disk via `SavedDataType` + `Codec`.
- `BlastPhysicsConstants` — Tuning constants with two independent runtime-switchable profiles:
  - `PerformanceProfile` — Shockwave/crater settings (sectors, stride, lateral, budgets).
  - `ThermalProfile` — Thermal settings (thermalBudget, waterBudget, interval).
  - Both support per-parameter override via `/atomfall performance set <key> <value>` and `/atomfall thermal set <key> <value>`.
- `SurfaceHeightCache` — Captures original surface heights once per column. `getOrCapture()` guards against unloaded chunks with `level.hasChunk()`, returning `level.getSeaLevel()` as a safe fallback.
- `BlastMaterialRules` — Material-based blast resistance and failure thresholds.
- `BlastWorldMutations` — Centralizes low-side-effect block writes using `Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS` to prevent item-entity floods during large-scale world mutation.
- `WaterEvaporationUtil` — Breadth-first water removal for thermal flash boiling.

**`common/radiation/`** — Persistent radiation zone system:
- `RadiationSavedData` — Extends Minecraft `SavedData`, persisted to disk via `SavedDataType` + `Codec`.
- `RadiationZone` — Expanding contamination zone with Gaussian falloff.
- `RadiationSystem` — Per-entity exposure tracking. Uses `HashMap<UUID, CompoundTag>` (server thread only). Applies `ModMobEffects.RADIATION_SICKNESS` for accurate client overlay detection.

**`common/temperature/`** — Parallel to radiation:
- `TemperatureSavedData` — Also extends `SavedData` for disk persistence.
- `TemperatureZone` — Expanding heat zone. `shielding()` uses `canSeeSky` instead of `level.clip()` to avoid per-tick raytracing overhead.
- `TemperatureSystem` — Per-entity heat tracking.

**`common/item/`** — `DetonatorItem`, `AirdropDesignatorItem`, `GeigerCounterItem`, `HazmatArmorItem`, `RadiationFilterItem`, `TemperatureGaugeItem`.

**`registry/`** — All registry entries: `ModBlocks`, `ModItems`, `ModEntities`, `ModBlockEntities`, `ModMobEffects`, `ModCreativeTabs`. Uses direct `Registry.register()` with `Supplier<T>` wrappers instead of deferred registers.

**`client/`** — Client-only code:
- `AtomfallModClient` — Client entry point.
- `ClientVisualProfile` — Client-side visual settings.
- Renderers (`NuclearCloudRenderer`, `ShockwaveRingRenderer`, `ThermalPulseRingRenderer`, etc.).
- Client overlay (`AtomfallOverlay`) — Detects `RADIATION_SICKNESS` effect for accurate overlay rendering.
- Client mixin package (`com.codex.atomfall.client.mixin`).

### Command System

The `/atomfall` command tree provides runtime tuning:

```
/atomfall performance              # Show current shock profile + overrides
/atomfall performance <profile>    # Switch profile (high_fidelity/balanced/performance)
/atomfall performance set <key> <value>   # Override single parameter
/atomfall performance reset        # Clear all overrides

/atomfall thermal                  # Show current thermal profile + overrides
/atomfall thermal <profile>        # Switch profile (high/balanced/performance)
/atomfall thermal set <key> <value># Override single parameter
/atomfall thermal reset            # Clear all overrides

/atomfall heatwave                 # Spawn standalone heatwave (radius 300, 2400C, 60s)
/atomfall heatwave <radius> <temp> <duration>  # Custom heatwave
```

Override keys for `performance set`:
- `sectors`, `strideNear`, `strideMid`, `strideFar`
- `lateralNear`, `lateralMid`, `lateralFar`
- `craterBudget`, `shockBudget`

Override keys for `thermal set`:
- `thermalBudget`, `waterBudget`, `interval`

Overrides take effect immediately without restart.

### Blast Flow

1. A bomb block is placed & right-clicked with a detonator or triggered by redstone.
2. After fuse ticks (defined by `AtomicBombBlockEntity`), `NuclearBlast.detonate()` is called.
3. The blast spawns cloud/shockwave/thermal entities, creates persistent radiation and temperature zones, scorches terrain, and spawns fused glass.
4. `AtomfallEvents.onEndWorldTick()` processes active blasts and ticks radiation/temperature zones every server tick.

### Performance Characteristics

- **Crater edits** are pre-computed asynchronously (snapshot + `CompletableFuture` parallel strip processing) and applied from a queue over multiple ticks. After the queue is drained, `collapseCraterFloatingBlocks()` runs up to 3 passes to remove悬空 blocks caused by uneven column heights.
- **Shockwave shell** uses snapshot-based decision making with optional parallel compute (fan-out via `CompletableFuture` when target count > 120 and processors >= 4).
- **Psi-tiered reprocessing**: `processedSurfaceColumns` and `processedStructureColumns` store the max psi already applied per column (`Long2DoubleOpenHashMap`). When a stronger shockwave reaches the same column (`currentPsi > storedPsi + 1.5`), it is reprocessed. This ensures low-pressure precursors do not block later high-pressure destruction.
- **Block edits** use `Block.UPDATE_CLIENTS_ONLY` (flag=2) to avoid neighbor-update cascade, which is the dominant cost of large-scale world mutation.
- **Chunk safety**: `terrainAdvanceFactor` checks `level.hasChunk()` before any `getBlockState`/`getHeight` calls. `SurfaceHeightCache.getOrCapture` also guards against unloaded chunks internally.
- **Deferred edit deduplication**: `deferredEdits` for unloaded chunks are guarded by a `LongOpenHashSet` (`deferredColumns`) so the same `(x,z)` column is never queued twice.
- **Ready shock target budget**: `processReadyShockTargets` has its own `shockBlockBudget()` cap so side-effects (water evaporation, ignition, margin disturbance) don't monopolize the tick.
- **Independent performance profiles**: Shockwave/crater settings (`PerformanceProfile`) and thermal settings (`ThermalProfile`) are completely separate. Use `/atomfall performance` and `/atomfall thermal` to adjust them independently.
- **Structure scanning improvements**:
  - `processStructureColumn` uses `Heightmap.Types.WORLD_SURFACE` (includes leaves) instead of `MOTION_BLOCKING`.
  - `structureScanDepth` and `maxStructureEdits` are significantly increased to fully destroy trees and buildings.
- **Radiation/temperature sampling** no longer uses `level.clip()` raytracing; shelter/shielding is computed via step-wise `getBlockState` or `canSeeSky`.
- `tickNearbyEntities` runs every 10 ticks (not every tick) to reduce `getEntitiesOfClass` overhead.

## Build Configuration

- Fabric Loom 1.15.5 with Mojang mappings
- Fabric Loader 0.18.6 + Fabric API 0.141.3
- Gradle JVM args: `-Xmx1G`
- `org.gradle.parallel=true`, configuration cache disabled
