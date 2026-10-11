# POTATOTV Anti-Cheat Client

> Minecraft 基岩版与 Java 版的全平台反作弊系统，分玩家端与服务端插件两种部署形态。

[![CI](https://github.com/EPOTATOTV/PACC4_0/actions/workflows/ci.yml/badge.svg)](https://github.com/EPOTATOTV/PACC4_0/actions/workflows/ci.yml)
[![CodeQL](https://github.com/EPOTATOTV/PACC4_0/actions/workflows/codeql.yml/badge.svg)](https://github.com/EPOTATOTV/PACC4_0/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

## 项目简介

PACC（POTATOTV Anti-Cheat Client）有两套部署形态，共用同一个 PTV 管控层和检测引擎核心：

- **玩家端模式**：装在玩家设备上的本地服务，采集本机数据（内存、进程、输入设备、USB、屏幕、本机网络流量），做本地检测与留痕，出红屏强制警告，供管理员远程查端。不接入游戏服务器 API，不封禁玩家。
- **服务端插件模式**：跑在游戏服务器进程内的插件 / 模组，能读本服玩家数据，做移动、战斗、数据包、客户端标识、附加包等检测。默认只告警，踢出 / 封禁由服务器运维显式打开。

两种模式的检测结果经 PTV 关联到同一玩家的 PTEID，共同影响信誉评分。

## 两种形态对比

| 维度 | 玩家端 (Client) | 服务端插件 (Server) |
|---|---|---|
| 部署位置 | 玩家个人设备 | 游戏服务器进程内 |
| 数据来源 | 玩家本机（内存 / 进程 / 输入 / USB / 网络） | 本服务器可见的玩家数据 |
| 服务器数据 | 不获取 | 可获取（仅限本服务器） |
| 封禁能力 | 无，仅红屏警告 + 记录 | 可选，默认仅告警 |
| 红屏 / 查端 | 有 | 无（服务端日志取证） |
| 部署成本 | 玩家需安装客户端 | 服务器装插件，玩家零安装 |

## 三条不能破的约束

1. 玩家端不得有任何封禁 / 踢出能力，也不为第三方封禁系统预留接口；只做本地检测、留痕、红屏强制警告、远程查端。服务端插件只在本服务器内按配置踢出或临时封禁，默认必须是「仅告警」，不跨服务器、不对接第三方封禁系统。
2. 玩家端检测数据只来自玩家设备本机，不接入游戏服务器 API、不读游戏服务器数据库。服务端插件运行在游戏服务器进程内，可读本服务器数据。两种模式的检测结果与证据都只经 PACC 独立通道上报 PTV，PTV 不反向读取任何游戏服务器数据库。
3. 管理后台必须能独立部署，不能和游戏服务器产生部署耦合。

## 核心能力

- **三层深度检测**（玩家端）：网络代理层抓包解析行为数据流，屏幕与输入层做视觉与输入时序分析，系统进程层做内存 / 进程 / 注入 / 签名检测，三层证据加权融合后再判定。
- **检测引擎与平台解耦**：平台无关的核心（`pacc-client-core`、`pacc-server-core`）不依赖具体平台 API，Windows / Linux / Android / 各种服务端各自接一层薄适配。
- **服务端插件**：Paper / Spigot / Nukkit / Nukkit-MOT / AllayMC / Geyser 适配，Bukkit 系与基岩版各有一层共享适配。
- **PRL 规则语言**：检测规则用自研 DSL 编写，玩家端与服务端共用一套规则与热加载。
- **自研 PBP 二进制协议**：玩家端与 PTV 之间的事件 / 指令线格式，提供 Java / TS / Rust / C# / Python 运行时。
- **红屏强制警告**：全屏覆盖 + 键盘锁定（内核驱动 / 用户态低级钩子双后端），被检测者键盘锁定等待管理员查端，其他玩家收到公示红屏并可点「继续」。
- **PTEID 独立账号体系**：与游戏账号解耦，带信誉评分与检测记录。
- **远程查端**：屏幕共享、证据提取、强制结论（确认作弊 / 误报 / 待分析）后解锁。
- **跨平台更新器**：检查、断点续传、差分、校验、备份替换与回滚（`pacc-cross-platform-updater`）。
- **本地加密持久化**：关键配置与证据在本地加密保存并做 HMAC 防篡改，重启后保留。

## 技术栈

| 层级 | 技术 |
|---|---|
| 后端 | Java 21 + Spring Boot 3 |
| 前端 | TypeScript + React + Vite |
| 玩家端 | Java 21（检测核心 / 规则 / 更新核心均为纯 JDK 实现） |
| 服务端插件 | Java 21（Bukkit 系、Nukkit 系）+ Go（AllayMC） |
| 桌面壳 | Tauri v2（Rust） |
| 移动端 | Capacitor + 原生探针 |
| 通信协议 | 自研 PBP 二进制协议（WSS 长连接） |
| 数据库 | MySQL 8（生产）/ H2（本地开发） |
| 数据库迁移 | Flyway |
| 容器化 | Docker Compose |
| CI/CD | GitHub Actions + CodeQL |

## 目录结构

```
pacc/
├── pacc-binary-protocol/        # 自研二进制协议 PBP（mdl 定义 + 五种语言运行时）
├── pacc-cross-platform-updater/ # 跨平台更新核心 PCU（检查/下载/差分/校验/回滚）
├── pacc-rule-language/          # PRL 检测规则语言（词法/语法/编译/沙箱/热加载）
├── pacc-client-core/            # 玩家端检测核心（Mod 扫描、移动/战斗检测、红屏与键盘锁抽象）
├── pacc-server-core/            # 服务端插件模式检测引擎核心（平台无关）
├── pacc-addon-scanner/          # 基岩版 Addon 扫描器（行为包/资源包/脚本）
├── pacc-forge-mod/              # Forge 模组（打包 pacc-client-core）
├── pacc-fabric-mod/             # Fabric 模组（打包 pacc-client-core）
├── server-plugins/              # 服务端插件（见下方「服务端插件」）
├── ptv-backend/                 # PTV 管控后端（Java 21 + Spring Boot 3）
├── ptv-frontend/                # 管理后台 / 玩家门户前端（TS + React + Vite）
├── ptv-client/                  # 玩家端用户态服务（Java 21，三层检测）
├── ptv-desktop/                 # 桌面壳（Tauri：红屏、键盘锁、Java 进程托管）
├── ptv-mobile/                  # 移动端探针（Capacitor + 原生）
├── platform/                    # 平台适配（内核驱动 / Java Agent / Linux / 移动端 / 原生库）
├── deploy/                      # 部署编排（网关 / 下载站 / 监控 / Helm / K8s / 单机脚本）
├── sdk/                         # 插件开发 SDK（java / javascript）
├── tools/
│   ├── windows-gui/             # Windows 管理工具（C#/.NET：安装 / 诊断 / 探针更新）
│   ├── installer/               # Inno Setup 安装向导
│   └── pbpgen/                  # PBP 代码生成器（生成五种语言的消息类）
├── proto/                       # 旧 Protocol Buffers 定义（已被 PBP 取代，仅存档）
├── scripts/                     # 构建 / 版本 / 签名 / 升级 / 回滚脚本
├── docker-compose.yml           # 一体化部署编排（推荐）
├── .env.example                 # 部署环境变量模板
└── .github/                     # CI/CD 流水线
```

## 快速开始

### 环境要求

- **JDK 21 + Maven**（后端、玩家端与仓库内 Java 模块）
- **Node.js 18+**（前端）
- **Docker + Docker Compose**（一体化部署）

### 先装仓库内模块

仓库没有 root 聚合 pom。`ptv-backend` 依赖 `pacc-binary-protocol`，`ptv-client` 还依赖 `pacc-cross-platform-updater`，这两个模块没发到中央仓库，首次构建前要先装进本地 Maven 仓库：

```bash
mvn -B -f pacc-binary-protocol/runtime-java/pom.xml install   # PBP：后端和玩家端都要
mvn -B -f pacc-cross-platform-updater/pom.xml      install   # PCU：只有玩家端要
```

### 管理后台后端

```bash
cd ptv-backend
mvn clean package
java -jar target/ptv-backend-*.jar
```

### 管理后台前端

```bash
cd ptv-frontend
npm install
npm run dev
# 开发模式，默认 http://localhost:5173
```

### 玩家端用户态服务

```bash
cd ptv-client
mvn clean package
java -jar target/ptv-client-*.jar
# 配置：外部工作目录下的 pacc-client.properties，或 PACC_CLIENT_* 环境变量
# 生产端点（server.uri / wss.uri）已内置在代码默认值里，随 POB 加密，不再随包附带明文配置
```

### 服务端插件

在 `server-plugins/` 下按目标服务端构建对应模块，产物放入服务端的 `plugins/`（AllayMC 为插件目录解压，Geyser 为 `extensions/`）：

| 模块 | 目标环境 | 产物 |
|---|---|---|
| `pacc-bukkit` | Bukkit API 共享适配层（Paper/Spigot 共用） | — |
| `pacc-paper` | Paper 1.20 / 1.21 | `pacc-paper-*.jar` |
| `pacc-spigot` | Spigot 1.20 / 1.21 | `pacc-spigot-*.jar` |
| `pacc-bedrock` | 基岩版共享适配层（Nukkit/Nukkit-MOT 共用） | — |
| `pacc-nukkit` | Nukkit | `pacc-nukkit-*.jar` |
| `pacc-nukkit-mot` | Nukkit-MOT | `pacc-nukkit-mot-*.jar` |
| `pacc-allaymc` | AllayMC（Go） | `pacc-allaymc-*.tar.gz` |
| `pacc-geyser` | Geyser 扩展 | `pacc-geyser-*.jar` |

服务端检测默认只告警，踢出 / 封禁需在插件配置里显式打开。

### 一体化部署（Docker Compose，推荐）

```bash
# 1. 准备环境变量（生产务必修改全部密钥）
cp .env.example .env

# 2. 构建并启动（MySQL + 后端 + 管理后台前端）
docker compose up -d --build

# 3. 查看健康状态（全部 healthy 即就绪）
docker compose ps

# 4. 验证
curl http://localhost:8080/actuator/health   # 后端
curl http://localhost:8081/healthz           # 前端

# 5. 查看日志 / 停止
docker compose logs -f ptv-backend
docker compose down
```

### 访问入口

| 服务 | 地址 | 说明 |
|---|---|---|
| 管理后台前端 | http://localhost:8081 | 登录密钥见 `.env` |
| 后端 REST | http://localhost:8080 | `/actuator/health` 健康检查 |
| 玩家端 WSS | ws://localhost:8080/ws/ptv | 玩家长连接 |

部署到公网时，官方入口为四个子域，经统一网关转发：

| 用途 | 域名 |
|---|---|
| 管理后台 | `admin.your-domain.com` |
| API / WebSocket | `api.your-domain.com` |
| 客户端下载站 | `dl.your-domain.com` |
| 玩家连接 | `pacc.your-domain.com` |

## 下载渠道

下载站（`deploy/dl-web/`）按平台分类，当前提供 13 个渠道：Windows、Linux、Android、iOS / iPadOS 四个玩家端；Paper、Spigot、Nukkit、Nukkit-MOT、AllayMC 五个服务端；Forge、Fabric 两个客户端模组；Geyser 扩展；Apollo 配置模板。渠道清单与版本号在 `deploy/dl-web/js/platforms.js` 和 `js/config.js` 维护，法务页在 `deploy/dl-web/legal/`。

## 数据库

- **生产环境**默认使用 **MySQL 8**，表结构由 Flyway 版本化迁移管理（`ptv-backend/src/main/resources/db/migration/`）
- **本地开发** profile 使用 **H2 内存库**，无需手动建表

## 环境变量

部署相关密钥与配置一律通过环境变量 / `.env` 注入（模板见 `.env.example`），不写入源码。**生产环境请务必使用随机强密钥覆盖默认值**。

| 变量 | 说明 | 示例 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `your-strong-password` |
| `PACC_ADMIN_API_KEY` | 管理后台登录密钥 | `your-login-key` |
| `PACC_SECURITY_JWT_SECRET` | JWT 签名密钥（≥32 字节） | `random-64-chars` |
| `PACC_SECURITY_WSS_SIGN_SECRET` | 玩家端 WSS 上报签名密钥（≥16 字节，须与客户端一致） | `random-32-chars` |

## 构建与测试

```bash
# 后端测试
cd ptv-backend && mvn test

# 前端测试
cd ptv-frontend && npm run test

# 仓库内独立模块（各自带 enforcer 依赖检查与单测）
mvn -B -f pacc-server-core/pom.xml package
mvn -B -f pacc-rule-language/pom.xml package
```

从 v5.4.0 起，发布件由 `scripts/build-all.sh` 一条命令产出，校验和写入 `dist/<version>/SHA256SUMS.txt`，签名走 `scripts/sign-artifacts.sh`。发布流水线（`.github/workflows/release.yml`）会额外构建 Windows 客户端安装包。

## 贡献指南

1. Fork 本仓库
2. 创建分支 `git checkout -b feature/xxx`
3. 提交改动 `git commit -m "feat: xxx"`
4. 推送分支 `git push origin feature/xxx`
5. 提交 Pull Request

> 提交前请确保通过 CI 检查（GitHub Actions 会自动运行测试与 CodeQL）。开发约定见 [AGENTS.md](AGENTS.md) 与 [DEVELOPER.md](DEVELOPER.md)。

## 许可证

本项目基于 [GNU Affero General Public License v3.0（AGPLv3）](LICENSE) 开源。

## 致谢

- [Spring Boot](https://spring.io/projects/spring-boot)
- [React](https://react.dev)
- [Vite](https://vitejs.dev)
- [Flyway](https://flywaydb.org)
- [Tauri](https://tauri.app)
