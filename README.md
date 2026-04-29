# Atomfall

Atomfall 是一个面向 **Minecraft 1.21.11 / Fabric** 的核爆与辐射主题模组。它围绕地表核装置展开，提供可调当量的爆炸、持续推进的冲击波、蘑菇云与热脉冲特效、爆后辐射污染、热区伤害以及地形灼烧和玻璃化效果。

这个项目仍处在实验开发阶段。核爆会大范围修改世界，请先在测试存档或备份后的世界里使用。

## 功能概览

- 地表核装置：默认当量 15 kt，可在 0.1 kt 到 50 kt 范围内调整。
- 爆炸模拟：生成火球、弹坑、冲击波、热脉冲、蘑菇云、辐射区和温度区。
- 地形效果：爆心弹坑、表层剥蚀、焦土、熔结玻璃、水体蒸发、植被和弱结构破坏。
- 持续世界计算：大型爆炸会分 tick 处理，并尽量避免一次性卡死服务器。
- 辐射系统：玩家和生物会受到辐射率、累积剂量和污染值影响。
- 温度系统：热区会引燃实体、造成高温伤害，并改变环境方块。
- 调试与调参命令：可在游戏内切换性能档位、热效应档位、冲击波日志和掉落控制。
- 客户端表现：自定义蘑菇云、冲击波环、热脉冲环和辐射覆盖层。

## 已注册内容

### 方块

- `atomfall:atomic_bomb` - Surface Nuclear Device，核心爆炸装置。
- `atomfall:scorched_earth` - 焦土，会随随机刻逐渐清除。
- `atomfall:fused_glass` - 高温玻璃化后的地表材料。

### 物品

- `atomfall:detonator` - Field Detonator，用于绑定、远程起爆和调整当量。
- `atomfall:geiger_counter` - Radiation Counter，读取当前辐射率、剂量和污染值。
- `atomfall:temperature_gauge` - Thermometer，读取当前位置温度阶段。

### 效果

- `atomfall:radiation_sickness` - 辐射病，会造成持续伤害和饥饿消耗。

## 基本玩法

1. 放置 `Surface Nuclear Device`。
2. 空手右键装置可查看当前当量、火球半径、弹坑半径、冲击波半径和热效应半径。
3. 使用 `Field Detonator` 调整或绑定装置：
   - 潜行右键：切换模式，依次为远程绑定、增加当量、降低当量。
   - 普通右键空气：在远程绑定模式下起爆已绑定装置；在当量模式下切换步进值。
   - 右键核装置：绑定或解绑装置，或按当前模式调整装置当量。
4. 也可以用打火石、火焰弹或红石信号直接引爆装置。
5. 装置被引爆后有约 5 秒引信，然后开始爆炸模拟。

## 命令

所有命令都以 `/atomfall` 开头。

```text
/atomfall performance
/atomfall performance high_fidelity
/atomfall performance balanced
/atomfall performance performance
/atomfall performance set <key> <value>
/atomfall performance reset
```

冲击波和弹坑相关参数：

```text
sectors
strideNear
strideMid
strideFar
lateralNear
lateralMid
lateralFar
craterBudget
shockBudget
```

冲击波性能日志：

```text
/atomfall performance log
/atomfall performance log on
/atomfall performance log off
/atomfall performance log interval <ticks>
```

热效应档位与参数：

```text
/atomfall thermal
/atomfall thermal high
/atomfall thermal balanced
/atomfall thermal performance
/atomfall thermal set <key> <value>
/atomfall thermal reset
```

热效应参数：

```text
thermalBudget
waterBudget
interval
```

独立热浪测试：

```text
/atomfall heatwave
/atomfall heatwave <radius>
/atomfall heatwave <radius> <temperature>
/atomfall heatwave <radius> <temperature> <duration>
```

掉落控制：

```text
/atomfall drops status
/atomfall drops off
/atomfall drops on
```

`/atomfall drops off` 会同时关闭方块、生物和实体掉落，适合大型爆炸测试时减少掉落实体压力。

## 构建与运行

### 环境要求

- Java 21
- Minecraft 1.21.11
- Fabric Loader 0.18.6 或更高
- Fabric API 0.141.3+1.21.11
- Fabric Loom 1.15.5

本项目使用 Mojang 官方映射。

### Windows PowerShell

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
```

### macOS / Linux

```bash
./gradlew build
./gradlew runClient
./gradlew runServer
```

构建后的 mod jar 位于：

```text
build/libs/
```

## 项目结构

```text
src/main/java/com/codex/atomfall/
  AtomfallMod.java                 主入口
  AtomfallEvents.java              服务端 tick、玩家和实体事件
  AtomfallCommands.java            /atomfall 命令
  common/block/                    核装置、焦土等方块
  common/item/                     引爆器、辐射计、温度计
  common/world/                    核爆、冲击波、弹坑和世界修改逻辑
  common/radiation/                辐射区、辐射存档和实体辐射采样
  common/temperature/              温度区、热效应和温度采样
  registry/                        方块、物品、实体、效果和创造模式标签注册

src/client/java/com/codex/atomfall/
  client/render/                   蘑菇云、冲击波环、热脉冲环渲染
  client/overlay/                  辐射视觉覆盖层

src/main/resources/assets/atomfall/
  lang/                            语言文件
  models/                          方块和物品模型
  textures/                        方块、物品、实体和 GUI 纹理
```

## 性能说明

Atomfall 的大型爆炸不是一次性完成的。弹坑开挖、冲击波采样、结构破坏、热效应和延迟方块编辑会拆分到多个 tick 里执行，部分计算会在后台线程预处理，再回到服务端线程应用结果。

默认性能档位是 `balanced`。如果服务器压力较高，可以使用：

```text
/atomfall performance performance
/atomfall thermal performance
```

如果需要更完整的破坏细节，可以切到：

```text
/atomfall performance high_fidelity
/atomfall thermal high
```

大型爆炸仍然可能造成明显卡顿，尤其是在高当量、复杂地形、大量可破坏结构或高视距环境下。

## 开发备注

- 客户端和通用代码通过 Fabric Loom 的 `splitEnvironmentSourceSets()` 分离。
- 活跃核爆、辐射区和温度区通过 Minecraft `SavedData` 持久化。
- 方块修改尽量使用抑制掉落的更新标志，避免爆炸制造大量掉落实体。
- 冲击波性能日志会写入当前游戏目录下的 `logs/atomfall-shock-perf.log`。

## 许可证

见 [LICENSE](LICENSE)。
