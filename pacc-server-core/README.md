# PACC Server Core

PACC 服务端插件模式（v6.0）的检测引擎内核。它把与平台无关的检测逻辑集中在这里：检查项、配置模型、检测引擎、上报边界。

纯 JDK 标准库实现，运行时不依赖任何第三方库。`maven-enforcer-plugin` 会在构建期拦下往主代码里新增的 compile / runtime 依赖。

## 模块职责

- 定义检测数据模型（`model` 包）：位置、战斗事件、数据包视图、Addon 描述、检测结果与上报载荷。
- 定义平台适配 SPI：`ServerPlatformAdapter`。
- 实现检查项（`check` 包）：移动、战斗、数据包、背包、交互、客户端标识、Addon、数据一致性。
- 检测引擎（`engine.DetectionEngine`）：分发事件、聚合结果、决定处置动作、上报。
- 上报接口（`report` 包）：`PtvServerReporter` 及空实现。

## 与各平台适配层的关系

本模块不认识任何具体服务端。Paper / Spigot / Nukkit / Nukkit-MOT / AllayMC / Geyser 各自提供一个薄适配层，实现 `ServerPlatformAdapter`，负责：

- 从平台事件里取出玩家位置、游戏模式、客户端标识、已装 Mod 与 Addon；
- 把 `alert` / `punish` 落成平台侧行为（聊天提示、踢出、临时封禁等）。

适配层拿到平台数据后调用 `DetectionEngine` 的 `handle*` 方法，引擎把结果聚合后再回调适配层。检测逻辑本身只消费传进来的数据，不读磁盘、不碰服务端内部 API。

## 数据边界

- 默认动作是**仅告警**（`punishmentMode` 默认 0）。踢出与封禁必须由运维显式打开 `punishmentMode`（1=踢出，2=封禁）才会发生。
- 检测数据只来自本服务端进程能观测到的玩家数据，不接入外部游戏服务器数据库。
- 日志与上报不写玩家原始数据，也不写密钥。

## 如何接入

1. 实现 `com.potatotv.pacc.server.core.ServerPlatformAdapter`。
2. 准备 `ServerPluginConfig`（用 `ServerPluginConfig.defaults()` 或 builder）。
3. 组装检查项列表，构造 `DetectionEngine`：

```java
ServerPlatformAdapter adapter = new MyPaperAdapter(...);
ServerPluginConfig config = ServerPluginConfig.defaults();
List<ServerCheck> checks = List.of(
        new MoveCheck(config),
        new CombatCheck(config),
        new PacketCheck(config),
        new ClientBrandCheck(config),
        new AddonCheck(config));
PtvServerReporter reporter = new MyPtvReporter(...);
DetectionEngine engine = new DetectionEngine(config, adapter, checks, reporter);

engine.handleMove(from, to);
engine.handleCombat(combatEvent);
```

4. 在平台事件回调里把数据喂给引擎；处置与上报由引擎统一处理。

## 包级检测能力矩阵

包级检测能到什么精度取决于平台给不给真实数据包。适配层启动时按
`PacketCapabilityMatrix` 打一条能力日志，运维据此判断「没告警」是没作弊还是查不了。

| 平台 | 包源 | 真实包 | 覆盖类型 | 穿墙检测 | 说明 |
|---|---|---|---|---|---|
| Paper / Spigot + PacketEvents | `packetevents-2.x` | 是 | 10/10 | 可用 | Netty 线程解析移动/挖掘/放置/交互/挥动/Pong/传送确认 |
| Paper / Spigot 无 PacketEvents | `bukkit-events` | 否 | 0/10 | 可用 | 退回事件口径，Timer/Blink 不可用 |
| Nukkit / Nukkit-MOT / AllayMC | `platform-events` | 否 | 0/10 | 视探针 | 只有事件模型，包级时序与序列断言不可用 |
| Geyser | `geyser-host-proxy` | 否 | 0/10 | 不可用 | 协议桥，能力随宿主形态；跨代理线索走 PTV 通道 |

真实包源由 `pacc-bukkit` 的 `PacketEventsBridge` 接入，依赖形态是 `provided` + `softdepend`，
不 shade 进插件产物（PacketEvents 自带 `plugin.yml`，打进来会撞；体量也不合适）。没装
PacketEvents 时桥类根本不会被加载，检测自动退回事件口径。

事务同步有个容易踩的坑：延迟补偿要求「服务端真的发出带 id 的事务包，客户端按 id 回 Pong」。
检测侧自己编 id 不会有任何 ack 回来，ack 率恒为 0，会把全服玩家判成 Disabler。所以
`ServerPlatformAdapter.transactionSender()` 返回 `null` 时，整条延迟/Disabler 判定直接放弃。

## 接入拉普拉斯精灵（被测目标适配）

拉普拉斯精灵是独立项目（`github.com/EPOTATOTV/LaplaceSprite`），本仓库**不复现平台功能**，
只提供「可被它接入」的适配面。平台侧按自己的适配器流程写一个实现
`io.laplace.sprite.adapters.sim.DetectionEngine` 的类，内部转调
`com.potatotv.pacc.server.core.laplace.LaplaceTargetFacade` 即可，两边零代码耦合。

门面暴露 12 个入口（10 类核心事件 + 刻 + 重置），返回 `LaplaceFlag`：

```java
LaplaceTargetFacade target = new LaplaceTargetFacade(config, adapter, checks, reporter);
List<LaplaceFlag> flags = target.onPacket(packetView);
target.onTick(System.currentTimeMillis());
target.reset(playerId);
```

`LaplaceFlag.flagId()` 是稳定标识（`检查项:作弊类型` 小写），平台按检测项/档位聚合检出率时
用它，不要解析 `detail` 文案。`evidenceRef` 从证据链 MAC 摘出，供平台归档对账。

契约元数据有两份等价来源，改常量时两边都要动（有单测守着）：

- `LaplaceTargetDescriptor.builtin(config)`：进程内直接用。
- `src/main/resources/pacc-laplace-target.json`：给平台侧工具读取，含事件入口、探针能力位、
  阈值锚点（注入幅度 = 阈值锚点 × 档位系数）。

探针能力位里的 `client_*` 一律为 `false`：客户端进程/模块/内存/USB 扫描由 `ptv-client`
经 PTV 通道上报，不在这个进程内目标里，不能声称支持。

## 构建与测试

```bash
mvn -B -f pacc-server-core/pom.xml package
```

`package` 会跑 enforcer 依赖检查与全部单元测试。
