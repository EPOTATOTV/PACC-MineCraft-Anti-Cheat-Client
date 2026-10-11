# PACC 桌面端客户端壳（ptv-desktop）

Tauri v2 + 复用 `ptv-frontend` 构建产物的桌面应用，负责把现有 React 玩家端/管理端页面封装为**可安装的桌面程序**，并托管 Java 客户端服务进程。

> 定位：`ptv-desktop` 是"壳"，真正的页面能力来自 `ptv-frontend` 的 `npm run build` 产物。本地开发引擎不变。

## 目录
- `src-tauri/`：Rust 源码与 Tauri 配置
  - `src/lib.rs`：系统托盘、多显示器红屏、键盘锁联动、协议确认、开机自启、Java 客户端进程管理与崩溃自愈、自更新编排、诊断导出
  - `src/winlock.rs`：键盘锁双后端（内核驱动 `\\.\PaccPtv` / 用户态低级钩子）
  - `src/local_client.rs`：Java 本地控制服务客户端（端口+token 发现）
  - `src/main.rs`：入口
  - `tauri.conf.json`：窗口（main / redscreen）、构建、打包、updater 端点
  - `capabilities/default.json`：权限
- `package.json`：`@tauri-apps/cli` 脚本

## 构建（需在装好 Rust 工具链的机器上）
```bash
# 首次：补 icons（先运行 tauri icon <任意1024px PNG> 生成 icons/）
cd ptv-desktop
npm install
npm run build                      # 自动先构建 ptv-frontend，再 tauri build
```
产物：
- Windows：`Release/bundle/msi/*.msi`、`Release/bundle/nsis/*.exe`
- macOS：`*.dmg` / `*.pkg`（需 macOS + Xcode 公证）
- Linux：`*.AppImage` / `*.deb` / `*.rpm`（systemd 由打包器托管 Java 服务）

## 关键实现点
- **红屏全屏（多显示器）**：后台线程轮询 Java 侧 `/status`，红屏由 false→true 时按 `available_monitors()` 为每个显示器创建 `redscreen-*` 窗口（`alwaysOnTop + fullscreen + 无装饰`），true→false 时全部销毁。窗口靠注入的 `window.__PACC_VIEW__='redscreen'` 让前端渲染专用红屏视图。
- **键盘锁联动**：红屏弹出时同步实施键盘锁，优先内核驱动（`\\.\PaccPtv`，backend=kernel），失败降级用户态低级钩子（`WH_KEYBOARD_LL`，backend=hook）。**只锁被检测者**：`/status` 的 `redscreen_is_target` 为假（公示给其他在线玩家）时保留键盘，前端显示「继续」按钮（调 `dismiss_redscreen` 仅关本机窗口）。
- **Java 服务进程**：启动时在资源目录查找 `ptv-client-*.jar`（经 `bundle.resources` 附带），`spawn` 该进程（env `PACC_DESKTOP_SHELL=tauri` 让 Java 不再自建 Swing 红屏），日志重定向到 `%ProgramData%\PACC\logs\client-tauri.log`；后台线程轮询，崩溃自动重启，连续崩溃达阈值（3 次）暂停并通知。
- **协议确认**：首次启动未接受当前版本协议时隐藏主窗口、弹出 `consent` 窗口，前端渲染协议视图（滚动到底部才能接受），结果写入 `%ProgramData%\PACC\consent.json`。
- **系统托盘**：显示主窗口 / 更新 Java 客户端 / 导出诊断 / 退出。
- **开机自启**：安装时默认开启，前端设置页可切换。
- **客户端自更新编排**：停服 → 备份 jar → 原子替换暂存制品 → 重启 → 健康检查，失败自动回滚；也可由托盘「更新 Java 客户端」触发。
- **发行加固**：`Cargo.toml` 的 `release` profile 已开 `strip` / `lto` / `codegen-units=1` / `panic="abort"`。
- **自动更新（壳自身）**：指向下载站域名，发布前需生成 Tauri 签名密钥并把 `pubkey` 填入 `tauri.conf.json`。

## 与 v5.0 移动端的关系
移动端（`../ptv-mobile`）为远程查看/管理角色，不内嵌本地检测引擎；桌面壳才承载本地 Java 检测引擎与红屏强制覆盖。