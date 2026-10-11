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

## 构建与测试

```bash
mvn -B -f pacc-server-core/pom.xml package
```

`package` 会跑 enforcer 依赖检查与全部单元测试。
