# PACC 检测引擎升级 — 安全审查报告（security-best-practices）

**审查范围**：本次变更引入的代码 —— `pacc-detection-engine`（全新模块 44 个主文件 + 22 个测试）、`pacc-server-core/intel`（新包 6 文件）、`ptv-client` EngineFingerprintDetector（新文件）与版本脚本断言。只报告本次变更引入的风险；skill 参考库不含 Java 条目，本报告按 Java/Maven 安全实践与仓库既有安全规范（AGENTS.md / project memory）人工审查。

** executive summary**：未发现可证实可利用的高危/严重漏洞。本次变更均为纯 JDK 零依赖实现，不新增网络入口、不执行外部命令、不反序列化外部数据。已修复 1 项中危（日志注入面），记录 4 项低危/加固建议。

## 已修复

### F1（中危）证据字段日志注入面 —— 已修复
- **位置**：`Evidence` 构造器、`FingerprintMatch.matchedValue`（本次变更引入的数据通道）
- **问题**：`detail` / `matchedValue` 承载来自 OS 侧的外部数据（进程名、窗口标题、文件名、游戏目录相对路径）。攻击者可将本机进程命名为含 CRLF/控制字符的值，经证据链进入服务端日志（server-core `buildAlert` 直接拼接 detail），违反仓库「日志必须做控制字符净化」的硬规范。
- **修复**：`Evidence.sanitize()` 在 record 构造器统一剥离控制字符（含换行）并截断至 4096 字符；`FingerprintMatch` 同样收敛。player 端/服务端所有证据均经此口。

## 无问题确认（逐项核查）

| 检查项 | 结论 |
|---|---|
| 命令注入 | 新代码零外部进程调用；OS 采集全部经 `OsProbe` 抽象由既有探针实现 |
| 反序列化 / 外部输入解析 | 无。指纹库/ML 权重为代码内置或 `fromFlat` 显式传入，无自动反序列化 |
| 密码学 | `EvidenceChain` 用 HmacSHA256；密钥构造时克隆、≥16 字节校验、`MessageDigest.isEqual` 常量时间比较 ✓；本次集成未接入密钥管理（无暴露面） |
| 依赖供应链 | enforcer 门禁禁第三方运行时依赖；测试期仅 JUnit ✓ |
| 正则 DoS | `MemoryPatternMatcher` 为手写线性匹配，无正则回溯风险 ✓ |
| 内存耗尽 | 各有状态检测器均有上限：PacketTiming 窗口 100、IntelRuntime 证据窗 64、MovementProfile 512、LatencyCompensator MAX_PENDING、WorldReplica 随玩家清理 ✓ |
| 玩家端能力边界 | 引擎仅产出 Evidence/Verdict，无封禁/踢出 API，符合 AGENTS.md 三条硬约束 ✓；服务端 intel 检查默认仅告警（punishmentMode=0）✓ |
| 数据边界 | 玩家端检测数据仅来自本机探针抽象；服务端仅消费平台事件视图（PacketView/CombatEvent），未接游戏服数据库 ✓ |
| 敏感信息泄露 | detail 不含路径全量/请求参数/密钥；UUID 不适用场景（无资源 ID 暴露） |

## 低危 / 加固建议（未修，不阻塞）

- **F2（低）`AttackPattern` / `StatOutlier` 状态无容量上限**：按玩家键控的窗口由 gameplay 速率天然限流，但恶意高频 CombatEvent（如插件误用）可放大内存。建议后续给窗口加与 PacketTiming 同款容量上限。
- **F3（低）`EngineFingerprintDetector` 每 30s 全量进程/窗口枚举**：走的是既有 `WindowsSystemProbe`（tasklist 有 3s 超时与 2s 缓存），无新增权限；但服务器高负载时会周期性拉起 tasklist。可考虑后续接入 PaccManager 原生探针通道。
- **F4（低）`MLDetector` 权重加载入口（fromFlat）未校验数值范围**：NaN/Inf 权重会污染置信度输出，但被 `ConfidenceScorer.mlGate(0.95)` 门槛兜住且权重来自可信云端下发。建议下发侧加签名校验（复用 PBP 通道已有 HMAC）。
- **F5（低）指纹库条目为启发式特征**：窗口标题/进程名子串匹配存在故意命名碰撞（玩家把计算器改名 "wurst-client.jar"）造成误报的可能——属于检测面固有属性而非漏洞，误报由风险等级门槛（单通道仅 ≥4 级出证）与多指标加权兜底。

**报告文件**：本文档。建议不提交 git（属内部审查产物，与仓库「内部设计文档不入库」规范一致）。
