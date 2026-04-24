# Atomfall Optimization Changes

## 10. 2026-04-24 Crater and Shockwave Follow-up

### 10.1 Crater core pillar cleanup
- `ActiveNuclearBlast.prepareCraterEditsAsync` now captures only the circular crater footprint and uses `WORLD_SURFACE` as the top bound.
- Crater floors use an origin-based bowl in the core, then blend back to local terrain near the rim. This prevents mountain columns from surviving as tall pillars inside the core.
- Added a budgeted multi-pass crater collapse phase so floating blocks left after excavation are removed across ticks instead of in one blocking pass.

### 10.2 Shockwave completeness fixes
- Added `sectorSampleFront` persistence so the visual/physics shock front can advance while block damage sampling catches up over later ticks.
- Loaded columns are marked in `processedSurfaceColumns` / `processedStructureColumns` as soon as they are queued, preventing repeated async batches from spending budget on the same column.
- Ready shock side effects now keep unprocessed targets when the tick budget runs out instead of clearing the whole queue.
- Async structure snapshots now use `WORLD_SURFACE` so leaves, trees, and exposed structures are included consistently.
- The safety timeout now waits for sampling, async batches, ready side effects, deferred edits, and shock edits to drain instead of dropping unfinished shockwave work.

### 10.3 Stronger scoured-surface visuals
- Surface shock edits now remove top layers and leave a scoured floor made from coarse dirt, gravel, cobblestone, tuff, scorched earth, and related materials.
- The scoured palette is driven by low-frequency value noise plus radial streak noise instead of per-column random hash, producing broader continuous blast bands and fewer speckled pixels.
- `shockScourDepth` now uses the same continuous damage/pattern model so depth and material choice match visually.

### 10.4 Chunk-ring shock sampling
- Replaced sector/radial/lateral shell sampling with radius-ring -> chunk -> chunk-local grid traversal.
- Ring processing is resumable across ticks with `shockChunkRing*` cursors and remains governed by `shockBlockBudget`.
- Chunk-ring traversal improves cache locality and avoids dropping unsampled shell slices when the budget is exhausted.
- Chunk-local sampling now uses per-chunk/ring phase offsets, deterministic jitter, and a surface-only footprint brush to avoid visible checkerboard damage patterns.
- Footprint brush targets are now surface-visual-only: only the center sample enters the water/ignition/margin side-effect queue, preventing the brush from multiplying ready targets.
- Shock `BlockEdit` work is de-duplicated by block position before application, so overlapping footprint samples replace older queued edits instead of growing the queue indefinitely.
- Shock edit application now scales up when the backlog is very large, helping drained queues catch up after heavy async batches.

### 10.5 Debug log command
- Added `/atomfall performance log` to show shock performance-log status.
- Added `/atomfall performance log on` and `/atomfall performance log off` to control log generation.
- Added `/atomfall performance log interval <ticks>` to tune the server-log interval.
- Shock logs are off by default and, when enabled, emit `Atomfall shock perf` lines with front/sample radius, ring range, chunks, sampled columns, queued targets, deferred targets, and queue backlogs.
- Shock performance logs are written to a dedicated `logs/atomfall-shock-perf.log` file under the current Minecraft game directory.
- Expanded shock performance lines with tick/phase timings, sample lag, crater/shock apply counts, edit dedupe counts, async batch completions, ready side-effect work, deferred scan/apply/trim counts, and item entity counts inside the blast area.
- Deferred shock work is now bucketed by chunk in memory while preserving exact per-column `PendingEdit` data in persistence.
- Deferred processing now skips unloaded chunks by bucket instead of repeatedly scanning every unloaded column, and logs `deferredBuckets=total/loaded/scanned/unloaded`.

### 10.6 Drop suppression
- `ActiveNuclearBlast` now uses `BlastWorldMutations.NO_DROP_FLAGS` for direct crater, shock, collapse, and ignition block writes.
- This keeps blast-driven block replacement client-visible while suppressing item drops from destroyed grass, leaves, crops, loose blocks, and similar states.

### 10.7 Radiation sampling
- `RadiationZone.shelterFactor` now checks chunk availability before reading blocks along the shelter ray.
- Unloaded chunks are skipped by chunk span instead of sampled block-by-block, preventing long-distance radiation checks from forcing synchronous `getChunkBlocking` loads.
- Loaded chunks still use the existing 1.25-block shelter sampling step, preserving exact shielding where terrain is available.

### 10.8 Profile tuning
- Updated shock performance defaults: `HIGH_FIDELITY` now uses 96 sectors and a 1600 shock budget; `BALANCED` uses 48 sectors and 1200 budget; `PERFORMANCE` uses 36 sectors and 1000 budget.
- Mid-range lateral sampling is enabled in the default profile so the visible destruction band is more continuous.

## 1. 掉落物洪峰修复

- 新增 `BlastWorldMutations.java`：统一使用 `Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS` 进行无掉落方块更新
- 替换 `TemperatureZone`、`WaterEvaporationUtil`、`ScorchedEarthBlock` 中的直接 `removeBlock` / `setBlock(..., 3)`

## 2. 冲击波/弹坑异步化

- `ActiveNuclearBlast.java`：新增专用线程池 `COMPUTE_EXECUTOR`
- crater 和 shock 的 `BlockEdit` 计算改为后台线程执行，主线程只负责生成 snapshot、轮询 future、按预算应用结果

## 3. 辐射/温度采样减负

- `RadiationSavedData` / `TemperatureSavedData`：改为继承 `SavedData`，使用 `SavedDataType` + `Codec` 持久化到磁盘
- `RadiationSystem` / `TemperatureSystem`：玩家采样短周期缓存，非玩家实体低频率采样
- `AtomfallEvents`：降低附近实体环境扫描频率（每 10 tick）

## 4. 坑体核心区打开速度

- `BlockEdit.apply(...)` 返回是否真正完成有效修改，无效编辑不消耗预算
- crater 列按距中心排序，优先打开核心区
- 前 5 tick 使用 6 倍预算、前 20 tick 使用 3 倍预算

## 5. 冲击波持久化

- 新增 `ActiveBlastSavedData.java`：用 `SavedData` 存储活动爆炸
- `ActiveNuclearBlast` 增加 `PersistenceState`，持久化前沿、能量、队列、deferred edits、surface height 等
- `SurfaceHeightCache` 增加快照和恢复能力

## 6. 温度效果增强与独立控制

- 新增 `/atomfall heatwave` 指令：只生成温度区，不触发核爆/冲击波/弹坑/辐射
- `processEnvironment` 采样密度：`min(budget*2, 60 + radius*0.35)`
- 核爆默认温度参数增强：`thermalRadius` ×1.4，`maxTemperatureC` ×1.25，寿命 60→120 秒
- `PerformanceProfile` 与 `ThermalProfile` 完全分离，各自独立调控
- 新增 `/atomfall thermal` 子命令和细粒度 `set` / `reset` override 机制

## 7. 严重 Bug 修复

### 7.1 冲击波永不过期
- 终止条件从 `averageFront >= shockSurfaceRadius + 64` 改为 `craterFinished && allSectorsMaxed() && ageTicks > 20`
- 增加安全超时：`ageTicks > shockSurfaceRadius / soundSpeed + 800`

### 7.2 实体死亡内存泄漏
- `ENTITY_UNLOAD` 只在区块卸载时触发，实体被杀死不触发
- 新增 `ServerLivingEntityEvents.AFTER_DEATH` 清理非玩家实体的辐射/温度数据

### 7.3 辐射/温度区数据不持久化
- `RadiationSavedData` / `TemperatureSavedData` 继承 `SavedData`，重启后区域和进度保留

### 7.4 `RadiationSicknessEffect` 伤害间隔不一致
- 删除 `applyEffectTick` 中的 `tickCount %` 检查，统一由 `shouldApplyEffectTickThisTick` 控制

### 7.5 客户端辐射覆盖层误触发
- 客户端覆盖层改为检查 `RADIATION_SICKNESS` 效果（而非 POISON/WITHER/NAUSEA）
- `RadiationSystem.applySymptoms` 在辐射率 ≥0.02 时施加自定义 `RADIATION_SICKNESS`

### 7.6 DetonatorItem 无效链接默认位置
- `readLinks` 中读取 `Pos` 时校验 `Long.MIN_VALUE`，跳过无效条目

### 7.7 冲击波每列只处理一次
- `processedSurfaceColumns` / `processedStructureColumns` 从 `LongOpenHashSet` 改为 `Long2DoubleOpenHashMap`
- 记录每列最大 psi，当 `currentPsi > storedPsi + 1.5` 时重新处理

### 7.8 弹坑柱子
- `prepareCraterEditsAsync` 预加载所有弹坑范围内 chunk，确保 `initialY` 精确
- 新增 `collapseCraterFloatingBlocks()`，3 轮 budget-limited 清理浮空方块

### 7.9 水蒸发与树叶烧毁失效
- 删除 `WaterEvaporationUtil.removeWaterNode` 中 `pos.equals(seed)` 特殊分支
- `TemperatureZone.processEnvironment` 覆盖层扫描从 `dy=1..2` 扩大到 `dy=1..8`，遇 LOGS 额外扫描树冠

### 7.10 弹坑 chunk key 打包错误
- `((long)(x >> 4) << 32) ^ (z >> 4)` 改为 `ChunkPos.asLong()`，避免负坐标符号扩展问题

## 8. 性能优化

### 8.1 Chunk 加载阻塞
- `terrainAdvanceFactor`、`emitShellParticles`、`processEnvironment` 均增加 `hasChunk` 前置检查
- `SurfaceHeightCache.getOrCapture` 内部再加 `hasChunk` 保护，未加载返回 `seaLevel`

### 8.2 辐射 `level.clip()` 双重计算
- 删除 `RadiationZone.shelterFactor()` 中的 `level.clip()`，保留步进循环衰减

### 8.3 温度 `shielding()` 光线追踪
- `TemperatureZone.shielding()` 改为 `level.canSeeSky()`，从光线追踪降级为高度表查询

### 8.4 deferredEdits 去重
- 新增 `LongOpenHashSet deferredColumns`，确保同一 `(x,z)` 列只加入一次

### 8.5 `processReadyShockTargets` Budget 控制
- 分配独立 `shockBlockBudget()`，避免副作用（水蒸发、点火、边缘扰动）单 tick 过量执行

### 8.6 PERFORMANCE 模式参数调整
- `shockSectors` 20→24，`strideNear/Mid/Far` 10/16/22→7/12/18，`lateralNear` 0→1
- `craterBudget` 600→800，`shockBudget` 400→600，`thermalBudget` 32→80

### 8.7 Structure 扫描增强
- `structureScanDepth`：30/22/16/12/8 → 48/36/26/18/12
- `maxStructureEdits`：28/18/12/8 → 64/48/32/20/14
- `processStructureColumn` 改用 `WORLD_SURFACE` heightmap（包含树叶）

### 8.8 客户端覆盖层实体查询缓存
- `AtomfallOverlay` 每 2 帧更新一次实体列表，合并三次 `getEntitiesOfClass` 为批量缓存

### 8.9 AtomicBombBlockEntity `setChanged()` 频率
- 只在 `fuseTicks % 10 == 0 || fuseTicks <= 5` 时调用

## 9. 后续建议

1. `RadiationZone.shelterFactor(...)` — 可做遮挡缓存或更粗糙的遮蔽近似
2. `processDeferredEdits(...)` — 可升级为按 chunk 分桶唤醒
3. crater/shock 的对象分配量 — 可考虑对象池或批量结构压缩
4. 更激进的地形改写 — 更高前期预算、更低保真 crater、甚至 chunk/section 级批量更新
