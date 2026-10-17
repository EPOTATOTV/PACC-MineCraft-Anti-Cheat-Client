# PACC 内核驱动（Windows CDP）

底层检测层（Windows），基于 WDM + 对象回调 + 内存取证，已实现为完整驱动逻辑源码。
真机部署需在 WDK10 + EV/WHQL 签名环境下用 `msbuild` 编译与签名。

## 能力
- **进程对象回调**：`ObRegisterCallbacks` 拦截对受保护进程的高危句柄访问
  （`PROCESS_VM_WRITE/VIRTUAL_MEMORY_OPERATION/CREATE_THREAD/SUSPEND` 等），
  非授权请求记入拦截计数并可裁剪权限，防注入/Hack。
- **内存特征扫描**：附加目标进程分块读取，通配符 `??` 逐字节比对，返回命中地址。
- **设备白名单**：校验 USB/HID 设备实例 ID 是否在注册表白名单内（宏键盘识别）。
- **IOCTL 协议**：受保护 PID 登记、内存扫描、拦截统计查询，供 ptv-client 驱动接口对接。
- **进程创建监控**：`PsSetCreateProcessNotifyRoutineEx`，用小尺寸可疑名单
  （调试器 / 注入工具 / 已知作弊加载器）标记启动，上报映像路径 + PID + 父 PID。
- **镜像加载监控**：`PsSetLoadImageNotifyRoutine`，识别未签名、临时目录路径、
  已知恶意基名前缀的 DLL/驱动加载，并标记注入保护进程的镜像。
- **线程创建监控**：`PsSetCreateThreadNotifyRoutine`，记录受保护进程内部新建线程
  （远程线程注入指示）。
- **注册表监控**：`CmRegisterCallback`，盯防 Image File Execution Options（调试器劫持）
  与 AppInit_DLLs（全局注入），上报键路径与被写入的值。
- **SSDT 完整性**：加载时定位并快照系统服务分派表条目，按需重读比对，报告分歧（内核 Hook 痕迹）。
- **IDT 完整性**：读 IDTR 后逐条目核对中断处理程序是否落在已加载内核模块内，报告异常条目
  （无需基线快照，也就不用怕"加载时已经被 Hook"）。
- **内核回调枚举**：定位进程 / 镜像通知回调数组，统计已注册项数并解析归属驱动名。
  注册表（`CmRegisterCallbackEx`）与对象（`ObRegisterCallbacks`）回调不是"全局数组 + 计数"结构，
  链表头随内核版本迁移，暂不枚举——这两类回 `Supported=FALSE`，用户态标记为"未知"而非 0。
- **驱动枚举 + 签名核验**：遍历 `PsLoadedModuleList`，读取每个映像的 PE 证书目录，
  标记未签名 / 非 Microsoft 驱动（Rootkit 检测）。
- **键盘过滤（输入暂停）**：kbfiltr 式键盘类过滤驱动，拦截
  `IOCTL_INTERNAL_KEYBOARD_CONNECT` 包装 kbdclass 的 `KeyboardClassServiceCallback`；
  红屏期间丢弃键盘输入包（鼠标保留，供管理员查端），支持令牌解锁与注册表持久化。
  只做输入暂停，不具备封禁能力。

## 目录结构
```
kernel-windows/
  pacc_common.h     -> 公共头（IOCTL / 结构 / 全局）
  pacc_driver.c     -> 驱动主入口（DriverEntry / IRP 分发 / 保护集合）
  pacc_event.c      -> 有界事件环形缓冲 + 内核侧字符串工具
  pacc_obhook.c     -> 对象回调（NtOpenProcess/DuplicateHandle 拦截）
  pacc_scanner.c    -> 内存特征扫描（分块 + ?? 通配）
  pacc_device.c     -> 设备白名单校验（注册表持久化）
  pacc_procmon.c    -> 进程 / 线程创建监控
  pacc_imagemon.c   -> 镜像（DLL / 驱动）加载监控
  pacc_regmon.c     -> 注册表监控（IFEO / AppInit_DLLs）
  pacc_ssdt.c       -> SSDT 完整性校验（快照 + 比对）
  pacc_drivenum.c   -> 已加载驱动枚举 + 签名核验
  pacc_kbdfilter.c  -> 键盘类过滤（输入暂停 / 令牌解锁 / 持久化）
  sources           -> WDK 构建清单
```

## 事件环形缓冲
- 事件统一为定长 `PACC_EVENT`（类别 / 标志 / PID / 父 PID / 时间戳 / 数值 / 明细串）。
- 缓冲在 `DriverEntry` 于**非分页池**一次性预分配 `PACC_EVENT_RING_CAPACITY = 256` 条，
  运行时**绝不增长**；全部读写受一把 `KSPIN_LOCK` 保护，可在 DISPATCH_LEVEL 回调中安全入队。
- **丢弃策略**：写满时丢弃最旧事件（Head 前移）并累加 `Dropped`——被洪泛也无法耗尽池。
- 所有内核通知回调内只做内存拷贝与入队，**不做任何 I/O / 阻塞等待**。

## IOCTL 协议（METHOD_BUFFERED）
| IOCTL | 输入 | 输出 | 说明 |
|-|-|-|-|
| 参见既有定义 | | | `0x8000+1..+4`：登记/清空保护 PID、内存扫描、拦截统计 |
| `+5` DRAIN_PROCESS | `PACC_DRAIN_REQUEST` | `PACC_EVENT_BATCH` + N×`PACC_EVENT` | 排空进程创建事件 |
| `+6` DRAIN_IMAGE | 同上 | 同上 | 排空镜像加载事件 |
| `+7` DRAIN_THREAD | 同上 | 同上 | 排空线程创建事件 |
| `+8` DRAIN_REGISTRY | 同上 | 同上 | 排空注册表事件 |
| `+9` RESET_EVENT_COUNTERS | 无 | `PACC_EVENT_STATS` | 归零全部计数器并返回快照 |
| `+10` QUERY_SSDT_STATUS | 无 | `PACC_SSDT_STATUS` | SSDT 条目数 / 分歧数 / 首个分歧项 |
| `+11` QUERY_DRIVER_STATUS | `PACC_DRIVER_QUERY` | `PACC_DRIVER_REPORT` | 驱动枚举 + 签名核验（`Entries` 变长，分页） |
| `+12` SET_KEYBOARD_LOCK | `PACC_KBD_LOCK_REQUEST` | 无 | 锁定/解锁键盘输入（解锁可要求令牌）；状态持久化到 `SOFTWARE\PACC` |
| `+13` QUERY_KEYBOARD_LOCK | 无 | `ULONG`（1/0） | 查询键盘是否处于锁定态 |
| `+14` QUERY_IDT_STATUS | 无 | `PACC_IDT_STATUS` | IDT 条目数 / 异常条目数 / 首个异常项 |
| `+15` QUERY_CALLBACK_STATUS | 无 | `PACC_CALLBACK_REPORT` | 内核回调注册枚举（每类独立标注是否可枚举） |

排空接口的用户侧 `MaxCount` 仅作上限参考，实际条数由输出缓冲与环形容量共同约束；
输出缓冲必须容纳头部 + 至少一条 `PACC_EVENT`。驱动状态查询以 `StartIndex/MaxCount` 分页，
单次上限 `PACC_DRIVER_MAX_PER_CALL`。

## 构建（需 Windows Driver Kit 10）
```bat
:: 在 "x64" 开发者命令提示符下
msbuild /t:build /p:Configuration=Release /p:TargetVersion=Win10
```
产出 `PaccPtv.sys`，经 EV 签名 + WHQL 后随玩家端安装包发布。
安装用 INF 见 `PaccPtv.inf`（供 `deploy/installer.ps1` 的 `pnputil /add-driver` 使用）。

WHQL / EV 认证前的测试阶段：用 `scripts/gen-test-cert.ps1` 生成测试证书，
再 `scripts/sign-artifacts.sh 5.4.0 --test` 做测试签名；目标机需 `bcdedit /set testsigning on` 才能加载。
安装时给 `deploy/installer.ps1` 加 `-EnableTestSigning` 可自动打开测试签名模式（仅测试机）。

## 说明
本源码为反作弊取证用途：仅当玩家显式安装反作弊系统且同意其条款时部署。
拦截策略默认"记录优先、按需拦截"，避免误伤正常系统与开发工具。

键盘过滤模块（`pacc_kbdfilter.c`）为 kbfiltr 式键盘类过滤，需 WDK 编译 + WHQL
签名后在真机验证；本仓库当前未在 Windows 上编译验证（开发机无 WDK）。