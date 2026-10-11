# PACC 配置中心（Apollo）接入说明

本目录是 PACC 后端接入 Apollo 配置中心的模板与说明。后端会把「检测阈值 / 规则开关 /
红屏策略 / 特征库版本 / 服务端插件」这类运行期参数从本地 `application.yml` 解耦出来，
改由 Apollo 集中下发并支持热更新。**Apollo 是可选增强，不是启动前提**：不接入、连不上或
引导未开启时，后端照常按本地 `application.yml` 的默认值运行。

## 命名空间

| 文件 | Apollo 命名空间 | 内容 |
|---|---|---|
| `application.properties` | `application` | 默认命名空间，放通用功能开关，作各业务命名空间的兜底 |
| `detection.rules.properties` | `detection.rules` | PRL 规则引擎开关与命中加分上限 |
| `detection.threshold.properties` | `detection.threshold` | 检测评分阈值、硬件/完整性/环境对抗阈值、零日融合权重 |
| `redscreen.policy.properties` | `redscreen.policy` | 红屏分级阈值与冷却窗口 |
| `feature.library.properties` | `feature.library` | 特征库版本锚点与热更新开关 |
| `server.plugin.properties` | `server.plugin` | 服务端插件沙箱参数与自动化开关 |

各文件的键名与后端 `application.yml` 中的 `pacc.*` 一一对应，Apollo 里的值会覆盖本地默认值。
文件内不含任何密钥；密钥一律经环境变量注入并由 `StartupSecretGuard` 在启动阶段做强校验（fail-closed），
不要把这些密钥搬进 Apollo。

## 导入 Apollo

1. 在 Apollo Portal 新建/选择应用（appId 默认 `pacc-ptv`，可用 `PACC_APOLLO_APP_ID` 覆盖）。
2. 在目标环境下依次创建上表列出的 6 个命名空间，类型选 **properties**（私有）。
3. 每个命名空间点「导入」，上传对应 `.properties` 文件（或手工粘贴键值）。
4. 按需修改值后发布（发布即触发热更新，在线玩家端会收到一条「配置已更新」通知）。

## 后端如何切到 Apollo

在部署环境设置：

| 环境变量 | 说明 | 默认 |
|---|---|---|
| `PACC_CONFIG_CENTER_PROVIDER` | 置为 `apollo` 才启用；`local` 表示只读本地 | `local` |
| `PACC_APOLLO_APP_ID` | Apollo 应用标识 | `pacc-ptv` |
| `PACC_APOLLO_META` | Apollo 配置服务地址，如 `http://apollo-config:8080` | 空 |
| `PACC_APOLLO_BOOTSTRAP_ENABLED` | 置为 `true` 才订阅远端变更（仅 provider=apollo 时有意义） | `false` |

## 降级行为

- `provider=local`（默认）：直接读 `application.yml` + 环境变量，不连 Apollo。
- `provider=apollo` 但引导未开启：仍读本地默认值，不订阅变更。
- `provider=apollo` 且引导成功后才能读到远端值；一旦远端不可达或读取超时，单次读取自动回退到
  调用方默认值并只记日志，不会抛异常、不会中断业务、更不会导致启动失败。
- 变更订阅失败同理：逐个命名空间 try/catch，失败仅告警，不影响应用启动。
