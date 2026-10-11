use std::fs::OpenOptions;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{json, Value};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_autostart::ManagerExt;

mod local_client;
mod winlock;
use local_client::ControlClient;
use winlock::KeyLock;

/// 持有 Java 客户端（ptv-client）子进程句柄，用于崩溃重启与生命周期管理。
struct ClientProc {
    child: Mutex<Option<Child>>,
    /// 连续崩溃次数；一次成功运行超过 STABLE_RUN_SECS 秒后清零。
    crash_count: AtomicU32,
    /// 最近一次成功启动的时刻，用于判定「是否稳定运行过」。
    started_at: Mutex<Option<Instant>>,
}

impl Default for ClientProc {
    fn default() -> Self {
        Self {
            child: Mutex::new(None),
            crash_count: AtomicU32::new(0),
            started_at: Mutex::new(None),
        }
    }
}

/// 连续崩溃达到该次数后暂停自动重启，避免无限拉起形成崩溃风暴。
const CRASHLOOP_THRESHOLD: u32 = 3;
/// 一次运行超过该秒数即视为稳定，崩溃计数清零。
const STABLE_RUN_SECS: u64 = 60;

/// 红屏弹出时锁定的键盘后端选择逻辑见 `winlock`：优先内核驱动，失败降级用户态钩子。

/// 展示红屏所需的前端 URL：从既有 `redscreen` 窗口取，保证与前端的 redscreen 路由一致；
/// 取不到 URL 时退回默认入口 `index.html`（前端需自己识别红屏场景）。
fn redscreen_url(app: &AppHandle) -> WebviewUrl {
    if let Some(w) = app.get_webview_window("redscreen") {
        if let Ok(u) = w.url() {
            match u.scheme() {
                "http" | "https" => return WebviewUrl::External(u.clone()),
                _ => {
                    let p = u.path().trim_start_matches('/');
                    if !p.is_empty() {
                        return WebviewUrl::App(p.into());
                    }
                }
            }
        }
    }
    WebviewUrl::App("index.html".into())
}

/// 是否为每个显示器都创建了可见的红屏窗口。
fn redscreen_visible(app: &AppHandle) -> bool {
    app.webview_windows()
        .values()
        .any(|w| w.label().starts_with("redscreen-") && w.is_visible().unwrap_or(false))
}

/// 打开红屏：枚举 `available_monitors()`，为每个显示器创建/复用一个覆盖全屏的置顶窗口，
/// 并同步实施键盘锁（内核优先，降级用户态钩子），最后 `POST /enforcement` 回报真实状态。
fn show_redscreen(app: &AppHandle, lock_keyboard: bool) -> Result<(), String> {
    let monitors = app.available_monitors().unwrap_or_default();
    let url = redscreen_url(app);
    let labels: Vec<String> = if monitors.is_empty() {
        // 枚举不到显示器时至少覆盖一个窗口，避免红屏完全不出现。
        vec!["redscreen-0".to_string()]
    } else {
        (0..monitors.len()).map(|i| format!("redscreen-{i}")).collect()
    };

    for (i, label) in labels.iter().enumerate() {
        let win = match app.get_webview_window(label) {
            Some(w) => w,
            None => WebviewWindowBuilder::new(app, label.clone(), url.clone())
                .title("PACC 红屏警告")
                .decorations(false)
                .always_on_top(true)
                .skip_taskbar(true)
                // 前端靠这个标记渲染红屏视图，避免依赖窗口标签解析（静态资源路由不带路径）。
                .initialization_script("window.__PACC_VIEW__='redscreen';")
                .visible(false)
                .build()
                .map_err(|e| format!("创建红屏窗口 {label} 失败: {e}"))?,
        };
        if let Some(m) = monitors.get(i) {
            let pos = m.position();
            let size = m.size();
            let _ = win.set_position(tauri::PhysicalPosition::new(pos.x, pos.y));
            let _ = win.set_size(tauri::PhysicalSize::new(size.width, size.height));
        }
        let _ = win.set_always_on_top(true);
        let _ = win.set_fullscreen(true);
        let _ = win.show();
        let _ = win.set_focus();
    }

    // 只锁被检测者的键盘：广播公示给其他在线玩家时保留键盘，让其能点「继续」。
    let lock = app.state::<KeyLock>();
    let (backend, driver_active) = if lock_keyboard {
        let b = lock.engage();
        (b, lock.driver_active())
    } else {
        lock.release();
        ("none".to_string(), lock.driver_active())
    };
    let _ = app
        .state::<ControlClient>()
        .post_enforcement(lock_keyboard, &backend, driver_active, true);
    Ok(())
}

/// 关闭红屏：销毁所有 `redscreen-*` 窗口，解除键盘锁并回报。
fn hide_redscreen(app: &AppHandle) {
    let labels: Vec<String> = app
        .webview_windows()
        .keys()
        .filter(|k| k.starts_with("redscreen-"))
        .cloned()
        .collect();
    for label in labels {
        if let Some(w) = app.get_webview_window(&label) {
            let _ = w.destroy();
        }
    }

    let lock = app.state::<KeyLock>();
    lock.release();
    let _ = app
        .state::<ControlClient>()
        .post_enforcement(false, &lock.backend(), lock.driver_active(), false);
}

/// 打开/关闭红屏全屏警告窗口，并联动键盘锁。
#[tauri::command]
fn toggle_redscreen(app: AppHandle, on: bool) -> Result<Value, String> {
    if on {
        // 手动触发（如联调）拿不到广播上下文，按被检测者处理；正常路径由 poll_and_emit 按 is_target 决定。
        let is_target = app
            .state::<ControlClient>()
            .get("/status")
            .ok()
            .and_then(|st| st["redscreen_is_target"].as_bool())
            .unwrap_or(true);
        show_redscreen(&app, is_target)?;
    } else {
        hide_redscreen(&app);
    }
    let lock = app.state::<KeyLock>();
    Ok(json!({
        "keyboard_backend": lock.backend(),
        "driver_active": lock.driver_active(),
    }))
}

/// 非被检测者的「继续」：只销毁本机红屏窗口，不动键盘锁与服务端告警状态。
/// 被检测者不得调用（前端按 `redscreen_is_target` 隐藏该按钮）。
#[tauri::command]
fn dismiss_redscreen(app: AppHandle) -> Result<(), String> {
    let labels: Vec<String> = app
        .webview_windows()
        .keys()
        .filter(|k| k.starts_with("redscreen-"))
        .cloned()
        .collect();
    for label in labels {
        if let Some(w) = app.get_webview_window(&label) {
            let _ = w.destroy();
        }
    }
    Ok(())
}

/// 单独实施/解除键盘锁（不涉及红屏），并回报真实状态。
#[tauri::command]
fn keyboard_lock(app: AppHandle, on: bool) -> Result<Value, String> {
    let lock = app.state::<KeyLock>();
    if on {
        let backend = lock.engage();
        let _ = app.state::<ControlClient>().post_enforcement(
            true,
            &backend,
            lock.driver_active(),
            redscreen_visible(&app),
        );
        Ok(json!({ "backend": backend, "driver_active": lock.driver_active() }))
    } else {
        lock.release();
        let _ = app.state::<ControlClient>().post_enforcement(
            false,
            "none",
            false,
            redscreen_visible(&app),
        );
        Ok(json!({ "backend": "none", "driver_active": false }))
    }
}

/// 显示主窗口（系统托盘菜单回调使用）。
#[tauri::command]
fn show_main_window(app: AppHandle) -> Result<(), String> {
    if let Some(win) = app.get_webview_window("main") {
        win.show().map_err(|e| e.to_string())?;
        win.unminimize().map_err(|e| e.to_string())?;
        win.set_focus().map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// 返回打包进资源目录的 Java 客户端 jar 路径。
fn client_jar(app: &AppHandle) -> Option<PathBuf> {
    let resource_dir = app.path().resource_dir().ok()?;
    for e in std::fs::read_dir(resource_dir).ok()?.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        if name.starts_with("ptv-client-") && name.ends_with(".jar") {
            return Some(e.path());
        }
    }
    None
}

const LOG_DIR: &str = r"C:\ProgramData\PACC\logs";
/// Java 客户端共享的查端屏幕共享凭据文件（RT 由客户端登录后以受限权限写入）。
const WS_CRED_FILE: &str = r"C:\ProgramData\PACC\ws-credentials.json";
/// 玩家端数据目录（协议确认等壳侧状态落在这里）。
const DATA_DIR: &str = r"C:\ProgramData\PACC";
/// 协议文本版本，随桌面壳版本变化；版本变了需要重新确认。
const CONSENT_VERSION: &str = env!("CARGO_PKG_VERSION");

/// 启动 Java 客户端服务：日志重定向到本机日志目录。
fn spawn_client(app: &AppHandle) {
    let Some(jar) = client_jar(app) else {
        eprintln!("未找到 ptv-client-*.jar，跳过 Java 服务启动");
        return;
    };
    let _ = std::fs::create_dir_all(LOG_DIR);
    let log_file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(PathBuf::from(LOG_DIR).join("client-tauri.log"))
        .ok();

    let mut cmd = Command::new("java");
    cmd.arg("-jar").arg(jar);
    // 告诉 Java 端红屏由桌面壳 WebView 渲染，别再自建 Swing 红屏。
    cmd.env("PACC_DESKTOP_SHELL", "tauri");
    match log_file {
        Some(f) => {
            let stderr = f.try_clone().expect("clone stderr");
            cmd.stdout(Stdio::from(f)).stderr(Stdio::from(stderr));
        }
        None => {
            cmd.stdout(Stdio::null()).stderr(Stdio::null());
        }
    }

    match cmd.spawn() {
        Ok(child) => {
            let state = app.state::<ClientProc>();
            *state.child.lock().expect("lock child") = Some(child);
            *state.started_at.lock().expect("lock started") = Some(Instant::now());
        }
        Err(e) => eprintln!("启动 Java 客户端失败: {e}"),
    }
}

/// 停止当前 Java 子进程（自更新时先用它腾出对 jar 的占用）。
fn stop_client(app: &AppHandle) {
    let state = app.state::<ClientProc>();
    let child = state.child.lock().expect("lock child").take();
    if let Some(mut child) = child {
        let _ = child.kill();
        let _ = child.wait();
    }
}

/// 崩溃自动重启：后台线程轮询子进程；连续崩溃达到阈值时暂停并通知用户。
fn supervise(app: AppHandle) {
    std::thread::spawn(move || loop {
        std::thread::sleep(Duration::from_secs(5));
        let state = app.state::<ClientProc>();
        let mut guard = state.child.lock().expect("lock child");
        let exited = guard
            .as_mut()
            .and_then(|c| c.try_wait().ok().flatten())
            .is_some();
        if !exited {
            continue;
        }
        *guard = None;
        drop(guard);

        let stable = state
            .started_at
            .lock()
            .expect("lock started")
            .map(|t| t.elapsed() >= Duration::from_secs(STABLE_RUN_SECS))
            .unwrap_or(false);
        if stable {
            state.crash_count.store(0, Ordering::SeqCst);
        }

        let count = state.crash_count.fetch_add(1, Ordering::SeqCst) + 1;
        if count >= CRASHLOOP_THRESHOLD {
            eprintln!("Java 客户端连续崩溃 {count} 次，暂停自动重启");
            let _ = app.emit("client_crashloop", json!({ "count": count }));
            // 暂停重启：本轮不再拉起，count 保留以便前端/托盘提示。
        } else {
            spawn_client(&app);
        }
    });
}

/// 生成诊断 JSON，供"诊断工具"导出。
#[tauri::command]
fn diagnostics_json(app: AppHandle) -> String {
    let jar = client_jar(&app).map(|p| p.display().to_string());
    let lock = app.state::<KeyLock>();
    let crash_count = app.state::<ClientProc>().crash_count.load(Ordering::SeqCst);
    serde_json::json!({
        "app": "PACC 玩家端",
        "version": env!("CARGO_PKG_VERSION"),
        "client_jar": jar,
        "log_dir": LOG_DIR,
        "consent": { "accepted": consent_accepted(), "version": CONSENT_VERSION },
        "keyboard_backend": lock.backend(),
        "driver_active": lock.driver_active(),
        "crash_count": crash_count,
        "generated_at": SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0),
    })
    .to_string()
}

/// 桌面桥：为前端"查端屏幕共享页"注入连接 /ws/ptv 所需的 pteid+token（B2）。
/// <p>玩家 JWT 存于 HttpOnly cookie，WebView 的 JS 无法读取，故由宿主桥转发：
/// 优先读 Java 客户端共享的本地凭据文件，其次回退环境变量；未注入时返回 Err，
/// 前端据此回退到 URL 参数传参。</p>
#[tauri::command]
fn screen_share_credentials() -> Result<serde_json::Value, String> {
    if let Ok(content) = std::fs::read_to_string(WS_CRED_FILE) {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&content) {
            let p = v.get("pteid").and_then(|x| x.as_str()).unwrap_or("");
            let t = v.get("token").and_then(|x| x.as_str()).unwrap_or("");
            if !p.is_empty() && !t.is_empty() {
                return Ok(serde_json::json!({ "pteid": p, "token": t }));
            }
        }
    }
    let pteid = std::env::var("PACC_SCREEN_PTEID").unwrap_or_default();
    let token = std::env::var("PACC_SCREEN_TOKEN").unwrap_or_default();
    if pteid.is_empty() || token.is_empty() {
        return Err("未注入查端屏幕共享凭据（PACC_SCREEN_PTEID/PACC_SCREEN_TOKEN 或 ws-credentials.json）".into());
    }
    Ok(serde_json::json!({ "pteid": pteid, "token": token }))
}

/// ---- 检测控制桥（React → Rust → Java 本地控制服务） ----

#[tauri::command]
fn detection_status(app: AppHandle) -> Result<Value, String> {
    app.state::<ControlClient>().get("/status")
}

#[tauri::command]
fn detection_start(app: AppHandle) -> Result<Value, String> {
    app.state::<ControlClient>().post("/start")
}

#[tauri::command]
fn detection_stop(app: AppHandle) -> Result<Value, String> {
    app.state::<ControlClient>().post("/stop")
}

#[tauri::command]
fn get_detections(app: AppHandle, limit: Option<u32>) -> Result<Value, String> {
    let n = limit.unwrap_or(20);
    app.state::<ControlClient>().get(&format!("/detections?limit={n}"))
}

#[tauri::command]
fn get_detection_config(app: AppHandle) -> Result<Value, String> {
    app.state::<ControlClient>().get("/config")
}

#[tauri::command]
fn update_detection_config(app: AppHandle, cfg: Value) -> Result<Value, String> {
    app.state::<ControlClient>().post_json("/config", cfg)
}

#[tauri::command]
fn pteid_info(app: AppHandle) -> Result<Value, String> {
    app.state::<ControlClient>().get("/pteid")
}

#[tauri::command]
fn open_logs() -> Result<(), String> {
    let _ = std::fs::create_dir_all(LOG_DIR);
    if cfg!(target_os = "windows") {
        Command::new("explorer")
            .arg(LOG_DIR)
            .spawn()
            .map_err(|e| e.to_string())
            .map(|_| ())
    } else {
        Command::new("xdg-open")
            .arg(LOG_DIR)
            .spawn()
            .map_err(|e| e.to_string())
            .map(|_| ())
    }
}

#[tauri::command]
fn set_autostart(app: AppHandle, enabled: bool) -> Result<(), String> {
    if enabled {
        app.autolaunch().enable().map_err(|e| e.to_string())
    } else {
        app.autolaunch().disable().map_err(|e| e.to_string())
    }
}

/// ---- 首次启动协议确认 ----

fn consent_file_path() -> PathBuf {
    if cfg!(target_os = "windows") {
        PathBuf::from(DATA_DIR).join("consent.json")
    } else {
        let home = std::env::var("HOME").unwrap_or_default();
        PathBuf::from(home).join(".config").join("pacc").join("consent.json")
    }
}

/// 读取已记录的协议版本；版本与当前 CONSENT_VERSION 一致才算已接受。
fn consent_state() -> (bool, Option<String>) {
    let raw = match std::fs::read_to_string(consent_file_path()) {
        Ok(s) => s,
        Err(_) => return (false, None),
    };
    let v: Value = match serde_json::from_str(&raw) {
        Ok(v) => v,
        Err(_) => return (false, None),
    };
    let accepted = v.get("accepted").and_then(|x| x.as_bool()).unwrap_or(false);
    let version = v.get("version").and_then(|x| x.as_str()).map(str::to_string);
    (accepted, version)
}

fn consent_accepted() -> bool {
    let (accepted, version) = consent_state();
    accepted && version.as_deref() == Some(CONSENT_VERSION)
}

#[tauri::command]
fn consent_status() -> Result<Value, String> {
    let (accepted, accepted_version) = consent_state();
    Ok(json!({
        "accepted": accepted && accepted_version.as_deref() == Some(CONSENT_VERSION),
        "version": CONSENT_VERSION,
        "accepted_version": accepted_version,
    }))
}

/// 接受协议：写入版本与时间戳，关闭协议窗口并显示主窗口。
#[tauri::command]
fn accept_consent(app: AppHandle, version: String) -> Result<Value, String> {
    let path = consent_file_path();
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let payload = json!({
        "accepted": true,
        "version": version,
        "accepted_at": SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0),
    });
    std::fs::write(&path, payload.to_string()).map_err(|e| format!("写入协议确认失败: {e}"))?;

    if let Some(w) = app.get_webview_window("consent") {
        let _ = w.destroy();
    }
    if let Some(main) = app.get_webview_window("main") {
        let _ = main.show();
        let _ = main.set_focus();
    }
    let _ = app.emit("consent_accepted", json!({ "version": version }));
    Ok(payload)
}

/// ---- Java 客户端自更新编排 ----

/// 定位暂存更新：Java 侧 `ops/UpdateService` 把通过校验的制品暂存到
/// `<安装目录>/pcu-staging/<版本>/pacc-update.bin`（见该类 STAGING_DIR/ARTIFACT_NAME）。
/// 安装目录即运行中 jar 所在目录，故以 jar 父目录为基点扫描，取最新的一份。
fn find_staged_update(jar: &std::path::Path) -> Option<PathBuf> {
    let install_dir = jar.parent()?;
    let staging = install_dir.join("pcu-staging");
    let mut newest: Option<(SystemTime, PathBuf)> = None;
    for entry in std::fs::read_dir(&staging).ok()?.flatten() {
        let artifact = entry.path().join("pacc-update.bin");
        if let Ok(meta) = std::fs::metadata(&artifact) {
            if meta.is_file() {
                let modified = meta.modified().unwrap_or(UNIX_EPOCH);
                if newest.as_ref().map(|(t, _)| modified > *t).unwrap_or(true) {
                    newest = Some((modified, artifact));
                }
            }
        }
    }
    newest.map(|(_, p)| p)
}

/// 轮询等待 Java 控制服务重新就绪（自更新后的健康检查）。
fn wait_client_ready(app: &AppHandle, secs: u64) -> bool {
    let ctl = app.state::<ControlClient>();
    let deadline = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(500));
        if ctl.refresh().is_err() {
            continue;
        }
        if ctl.get("/status").is_ok() {
            return true;
        }
    }
    false
}

/// 管理器进程式的应用更新：停服 → 备份 → 原子替换 → 重启 → 健康检查 → 失败回滚。
fn update_client_blocking(app: &AppHandle) -> Result<Value, String> {
    let jar = client_jar(app).ok_or("未找到已安装的 ptv-client jar")?;
    let staged = find_staged_update(&jar).ok_or(
        "未发现暂存更新（pcu-staging/*/pacc-update.bin），请先让客户端完成下载与校验",
    )?;
    let bak = jar.with_extension("jar.bak");
    let tmp = jar.with_extension("jar.new");

    // 1) 停止当前服务，腾出对 jar 的占用
    stop_client(app);
    // 2) 备份旧 jar
    let _ = std::fs::remove_file(&bak);
    std::fs::rename(&jar, &bak).map_err(|e| format!("备份旧 jar 失败: {e}"))?;
    // 3) 原子替换：先写临时文件再 rename 覆盖
    let replaced = (|| -> Result<(), String> {
        std::fs::copy(&staged, &tmp).map_err(|e| format!("写入临时 jar 失败: {e}"))?;
        std::fs::rename(&tmp, &jar).map_err(|e| format!("替换 jar 失败: {e}"))?;
        Ok(())
    })();
    if let Err(e) = replaced {
        let _ = std::fs::remove_file(&tmp);
        let _ = std::fs::rename(&bak, &jar); // 回滚
        spawn_client(app);
        return Err(format!("应用更新失败，已回滚: {e}"));
    }
    // 4) 重启并校验启动成功
    spawn_client(app);
    if wait_client_ready(app, 30) {
        Ok(json!({
            "ok": true,
            "applied_from": staged.display().to_string(),
            "backup": bak.display().to_string(),
        }))
    } else {
        // 5) 健康检查失败 → 回滚到旧版本
        stop_client(app);
        let _ = std::fs::remove_file(&jar);
        let _ = std::fs::rename(&bak, &jar);
        spawn_client(app);
        Err("更新后客户端未在 30 秒内就绪，已回滚到旧版本".to_string())
    }
}

/// 应用 Java 客户端更新（供托盘菜单/前端调用）。阻塞式编排放到阻塞线程池，避免卡住 UI。
#[tauri::command]
async fn update_client(app: AppHandle) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || update_client_blocking(&app))
        .await
        .map_err(|e| e.to_string())?
}

/// 后台轮询 Java 侧状态，向 WebView 推送状态/告警/红屏事件（事件名见设计文档 5.1.2）。
/// 红屏由 false→true 时同时执行多显示器红屏 + 键盘锁，true→false 时反向解除；
/// 二者都走 `show_redscreen`/`hide_redscreen`，与前端手动 `toggle_redscreen` 共用同一实现。
fn poll_and_emit(app: AppHandle) {
    let ctl = app.state::<ControlClient>();
    let _ = ctl.refresh();
    let mut prev_event = String::new();
    let mut prev_rs = false;
    loop {
        std::thread::sleep(Duration::from_secs(10));
        if let Ok(st) = ctl.get("/status") {
            let cur_event = st["last_event_type"].as_str().unwrap_or("").to_string();
            let rs = st["redscreen_active"].as_bool().unwrap_or(false);
            let _ = app.emit("status_update", st.clone());
            if !cur_event.is_empty() && cur_event != prev_event {
                let _ = app.emit(
                    "detection_alert",
                    json!({ "event_type": cur_event, "level": st["redscreen_level"] }),
                );
            }
            if rs && !prev_rs {
                let is_target = st["redscreen_is_target"].as_bool().unwrap_or(true);
                if let Err(e) = show_redscreen(&app, is_target) {
                    eprintln!("显示红屏失败: {e}");
                }
                let _ = app.emit(
                    "redscreen_triggered",
                    json!({ "level": st["redscreen_level"] }),
                );
            } else if !rs && prev_rs {
                hide_redscreen(&app);
                let _ = app.emit("redscreen_unlocked", json!({}));
            }
            prev_event = cur_event;
            prev_rs = rs;
        }
    }
}

/// 桌面壳入口：封装 React GUI（继承自 ptv-frontend）+ Java 客户端进程 + 系统托盘 + 红屏窗口。
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--auto-start"]),
        ))
        .plugin(tauri_plugin_updater::Builder::new().build())
        .manage(ClientProc::default())
        .manage(ControlClient::default())
        .manage(KeyLock::default())
        .setup(|app| {
            // 系统托盘
            let i_main = MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>)?;
            let i_update = MenuItem::with_id(app, "update", "更新 Java 客户端", true, None::<&str>)?;
            let i_diag = MenuItem::with_id(app, "diag", "导出诊断", true, None::<&str>)?;
            let i_quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&i_main, &i_update, &i_diag, &i_quit])?;

            TrayIconBuilder::with_id("pacc-tray")
                .icon(app.default_window_icon().expect("default icon").clone())
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "show" => {
                        let _ = show_main_window(app.clone());
                    }
                    "update" => {
                        let handle = app.clone();
                        std::thread::spawn(move || match update_client_blocking(&handle) {
                            Ok(v) => {
                                let _ = handle.emit("client_update", json!({ "ok": true, "detail": v }));
                            }
                            Err(e) => {
                                eprintln!("更新 Java 客户端失败: {e}");
                                let _ = handle.emit("client_update", json!({ "ok": false, "error": e }));
                            }
                        });
                    }
                    "diag" => {
                        let _ = app.emit("diagnostics", diagnostics_json(app.clone()));
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .build(app)?;

            // 开机自启（安装时默认开启，可在设置中关闭）
            let _ = app.autolaunch().enable();

            // Java 客户端进程管理与崩溃自愈
            let handle = app.handle().clone();
            spawn_client(&handle);
            supervise(handle.clone());
            // 本地控制服务状态轮询：向 WebView 推送状态/红屏/告警事件
            std::thread::spawn(move || poll_and_emit(handle));

            // 首次启动协议确认：未接受则隐藏主窗口，弹出协议窗口（`index.html#/consent`）。
            if !consent_accepted() {
                if let Some(main) = app.get_webview_window("main") {
                    let _ = main.hide();
                }
                if app.get_webview_window("consent").is_none() {
                    let _ = WebviewWindowBuilder::new(
                        app,
                        "consent",
                        WebviewUrl::App("index.html".into()),
                    )
                    .title("PACC 玩家协议")
                    .inner_size(760.0, 580.0)
                    .resizable(false)
                    .center()
                    // 前端靠这个标记渲染协议确认视图（见 App.tsx）。
                    .initialization_script("window.__PACC_VIEW__='consent';")
                    .build();
                }
            }

            let _ = app.emit(
                "app-ready",
                serde_json::json!({ "version": env!("CARGO_PKG_VERSION") }),
            );
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            toggle_redscreen,
            keyboard_lock,
            dismiss_redscreen,
            show_main_window,
            diagnostics_json,
            screen_share_credentials,
            detection_status,
            detection_start,
            detection_stop,
            get_detections,
            get_detection_config,
            update_detection_config,
            pteid_info,
            open_logs,
            set_autostart,
            consent_status,
            accept_consent,
            update_client
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application")
}