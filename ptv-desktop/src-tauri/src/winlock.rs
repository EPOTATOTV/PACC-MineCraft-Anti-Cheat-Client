//! 红屏期间的键盘锁实施层。
//!
//! 目标：红屏弹出时真正暂停被检测玩家的键盘输入，鼠标保留不动。
//!
//! 两条后端，按优先级降级：
//! 1. 内核驱动 `\\.\PaccPtv`（IOCTL METHOD_BUFFERED）。WHQL 认证期间驱动不可用，
//!    或安装环境未加载驱动时，打开设备会失败。
//! 2. 用户态低级键盘钩子 `WH_KEYBOARD_LL`。驱动不可用时的降级实现，只挂键盘、
//!    不碰鼠标，装钩线程跑专属消息泵。
//!
//! 两条都失败时后端为 `none`，交由上层如实回报（而不是假装锁上了）。

use std::sync::Mutex;

#[cfg(target_os = "windows")]
mod platform {
    use super::Mutex;
    use std::ffi::{c_void, OsStr};
    use std::os::windows::ffi::OsStrExt;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc;
    use std::thread::JoinHandle;
    use std::time::Duration;

    use windows::core::PCWSTR;
    use windows::Win32::Foundation::{
        CloseHandle, ERROR_SUCCESS, GENERIC_READ, GENERIC_WRITE, HANDLE, LPARAM, LRESULT, WPARAM,
    };
    use windows::Win32::Storage::FileSystem::{
        CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_EXISTING,
    };
    use windows::Win32::System::IO::DeviceIoControl;
    use windows::Win32::System::Registry::{RegGetValueW, HKEY_LOCAL_MACHINE, RRF_RT_REG_DWORD};
    use windows::Win32::System::Threading::GetCurrentThreadId;
    use windows::Win32::UI::WindowsAndMessaging::{
        CallNextHookEx, GetMessageW, PeekMessageW, PostThreadMessageW, SetWindowsHookExW,
        UnhookWindowsHookEx, MSG, PM_NOREMOVE, WH_KEYBOARD_LL, WM_QUIT,
    };

    // ---- 与内核驱动共享的 IOCTL 契约 ----
    //
    // CTL_CODE(DeviceType, Function, Method, Access)
    //   = (DeviceType << 16) | (Access << 14) | (Function << 2) | Method
    // 其中：
    //   DeviceType = FILE_DEVICE_UNKNOWN = 0x22
    //   Method     = METHOD_BUFFERED   = 0x0
    //   Access     = FILE_ANY_ACCESS   = 0x0
    //   Function   = 0x8000 + 12（SET）与 0x8000 + 13（QUERY）
    // 代入即得下面两个常量：SET = 0x00240030，QUERY = 0x00240034。
    const fn ctl_code(device_type: u32, function: u32, method: u32, access: u32) -> u32 {
        (device_type << 16) | (access << 14) | (function << 2) | method
    }
    const FILE_DEVICE_UNKNOWN: u32 = 0x22;
    const METHOD_BUFFERED: u32 = 0x0;
    const FILE_ANY_ACCESS: u32 = 0x0;
    const IOCTL_PACC_SET_KEYBOARD_LOCK: u32 =
        ctl_code(FILE_DEVICE_UNKNOWN, 0x8000 + 12, METHOD_BUFFERED, FILE_ANY_ACCESS);
    // 契约里保留的查询 IOCTL；驱动侧提供，壳层暂无调用点。
    #[allow(dead_code)]
    const IOCTL_PACC_QUERY_KEYBOARD_LOCK: u32 =
        ctl_code(FILE_DEVICE_UNKNOWN, 0x8000 + 13, METHOD_BUFFERED, FILE_ANY_ACCESS);

    const DEVICE_PATH: &str = r"\\.\PaccPtv";
    const REG_SUBKEY: &str = r"SOFTWARE\PACC";
    const REG_VALUE: &str = "KbdUnlockToken";

    /// 钩子过程读取的全局锁状态（钩子过程是 C 回调，拿不到 KeyLock 实例）。
    static HOOK_LOCKED: AtomicBool = AtomicBool::new(false);

    /// `WH_KEYBOARD_LL` 钩子过程：锁定时吞掉按键返回 1，否则原样透传。
    /// 该钩子只挂键盘，鼠标不受影响。
    unsafe extern "system" fn keyboard_hook_proc(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
        if code >= 0 && HOOK_LOCKED.load(Ordering::Relaxed) {
            // 返回非 0 即丢弃该事件，不再向后续钩子/目标窗口投递。
            return LRESULT(1);
        }
        CallNextHookEx(None, code, wparam, lparam)
    }

    fn to_wide(s: &str) -> Vec<u16> {
        OsStr::new(s).encode_wide().chain(std::iter::once(0)).collect()
    }

    /// 打开内核驱动设备；不存在或权限不足时返回 Err（据此降级到用户态钩子）。
    fn open_device() -> Result<HANDLE, String> {
        let path = to_wide(DEVICE_PATH);
        unsafe {
            CreateFileW(
                PCWSTR(path.as_ptr()),
                (GENERIC_READ | GENERIC_WRITE).0,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                None,
                OPEN_EXISTING,
                FILE_ATTRIBUTE_NORMAL,
                None,
            )
            .map_err(|e| format!("打开 {DEVICE_PATH} 失败: {e}"))
        }
    }

    /// 从 `HKLM\SOFTWARE\PACC\KbdUnlockToken` 读解锁令牌；缺失或类型不符按 0（不校验）处理。
    fn read_unlock_token() -> u32 {
        let subkey = to_wide(REG_SUBKEY);
        let value = to_wide(REG_VALUE);
        let mut data: u32 = 0;
        let mut size = std::mem::size_of::<u32>() as u32;
        let status = unsafe {
            RegGetValueW(
                HKEY_LOCAL_MACHINE,
                PCWSTR(subkey.as_ptr()),
                PCWSTR(value.as_ptr()),
                RRF_RT_REG_DWORD,
                None,
                Some(&mut data as *mut u32 as *mut c_void),
                Some(&mut size),
            )
        };
        if status == ERROR_SUCCESS {
            data
        } else {
            0
        }
    }

    /// 下发 SET_KEYBOARD_LOCK：输入 `{ ULONG Lock; ULONG Token; }`（8 字节）。
    /// 解锁时携带注册表令牌，驱动据此判定是否允许解锁。
    fn kernel_set_lock(lock: bool) -> Result<(), String> {
        let handle = open_device()?;
        let token = if lock { 0 } else { read_unlock_token() };
        let input: [u32; 2] = [u32::from(lock), token];
        let mut returned: u32 = 0;
        let res = unsafe {
            DeviceIoControl(
                handle,
                IOCTL_PACC_SET_KEYBOARD_LOCK,
                Some(input.as_ptr() as *const c_void),
                std::mem::size_of_val(&input) as u32,
                None,
                0,
                Some(&mut returned),
                None,
            )
        };
        unsafe {
            let _ = CloseHandle(handle);
        }
        res.map_err(|e| format!("IOCTL_PACC_SET_KEYBOARD_LOCK 失败: {e}"))
    }

    /// 用户态降级：装钩线程的句柄与线程 id。
    struct HookRuntime {
        tid: u32,
        join: Option<JoinHandle<()>>,
    }

    /// 装钩线程主体：装 WH_KEYBOARD_LL，建消息队列，跑 GetMessageW 消息泵，
    /// 收到 WM_QUIT 后退消息泵并卸载钩子。
    fn hook_thread_main(tx: mpsc::Sender<Result<u32, String>>) {
        let hook = match unsafe { SetWindowsHookExW(WH_KEYBOARD_LL, Some(keyboard_hook_proc), None, 0) }
        {
            Ok(h) => h,
            Err(e) => {
                let _ = tx.send(Err(format!("SetWindowsHookExW 失败: {e}")));
                return;
            }
        };
        // 低级键盘钩子要求装钩线程有消息循环；先 PeekMessageW 建出线程消息队列，
        // 保证卸载时 PostThreadMessageW 能唤醒本线程。
        let mut msg: MSG = unsafe { std::mem::zeroed() };
        unsafe {
            // 返回值只是 BOOL（此处仅为建队列，忽略结果）。
            let _ = PeekMessageW(&mut msg, None, 0, 0, PM_NOREMOVE);
        }
        let tid = unsafe { GetCurrentThreadId() };
        let _ = tx.send(Ok(tid));

        // GetMessageW 返回 0 表示收到 WM_QUIT（卸载请求），此时退出循环。
        while unsafe { GetMessageW(&mut msg, None, 0, 0) }.0 > 0 {}
        let _ = unsafe { UnhookWindowsHookEx(hook) };
    }

    /// `KeyLock` 运行状态。
    struct LockState {
        engaged: bool,
        backend: String,
        driver_ok: bool,
    }

    /// 键盘锁状态机。`Default` 即可用；`engage`/`release` 幂等。
    pub struct KeyLock {
        state: Mutex<LockState>,
        hook: Mutex<Option<HookRuntime>>,
    }

    impl Default for KeyLock {
        fn default() -> Self {
            Self {
                state: Mutex::new(LockState {
                    engaged: false,
                    backend: "none".to_string(),
                    driver_ok: false,
                }),
                hook: Mutex::new(None),
            }
        }
    }

    impl KeyLock {
        /// 实施键盘锁，返回实际生效的后端名（`kernel`/`hook`/`none`）。幂等。
        pub fn engage(&self) -> String {
            let mut st = self.state.lock().expect("lock keylock state");
            if st.engaged {
                return st.backend.clone();
            }
            // 优先内核路径
            match kernel_set_lock(true) {
                Ok(()) => {
                    st.engaged = true;
                    st.driver_ok = true;
                    st.backend = "kernel".to_string();
                }
                Err(e) => {
                    eprintln!("内核键盘锁不可用，降级用户态钩子: {e}");
                    st.driver_ok = false;
                    if self.install_hook().is_ok() {
                        HOOK_LOCKED.store(true, Ordering::SeqCst);
                        st.engaged = true;
                        st.backend = "hook".to_string();
                    } else {
                        st.engaged = false;
                        st.backend = "none".to_string();
                    }
                }
            }
            st.backend.clone()
        }

        /// 解除键盘锁。
        pub fn release(&self) {
            let mut st = self.state.lock().expect("lock keylock state");
            match st.backend.as_str() {
                "kernel" => {
                    if let Err(e) = kernel_set_lock(false) {
                        eprintln!("内核键盘解锁失败: {e}");
                    }
                }
                "hook" => {
                    // 先放开透传，再拆钩子，避免卸载窗口期内继续吞键。
                    HOOK_LOCKED.store(false, Ordering::SeqCst);
                    self.uninstall_hook();
                }
                _ => {}
            }
            st.engaged = false;
            st.driver_ok = false;
            st.backend = "none".to_string();
        }

        /// 当前后端名（`kernel`/`hook`/`none`）。
        pub fn backend(&self) -> String {
            self.state.lock().expect("lock keylock state").backend.clone()
        }

        /// 本次锁是否由内核驱动承载。
        pub fn driver_active(&self) -> bool {
            self.state.lock().expect("lock keylock state").driver_ok
        }

        fn install_hook(&self) -> Result<(), String> {
            let mut guard = self.hook.lock().expect("lock hook runtime");
            if guard.is_some() {
                return Ok(());
            }
            let (tx, rx) = mpsc::channel::<Result<u32, String>>();
            let join = std::thread::Builder::new()
                .name("pacc-kbd-hook".to_string())
                .spawn(move || hook_thread_main(tx))
                .map_err(|e| format!("创建钩子线程失败: {e}"))?;
            match rx.recv_timeout(Duration::from_secs(5)) {
                Ok(Ok(tid)) => {
                    *guard = Some(HookRuntime { tid, join: Some(join) });
                    Ok(())
                }
                Ok(Err(e)) => {
                    let _ = join.join();
                    Err(e)
                }
                Err(_) => Err("安装键盘钩子超时".to_string()),
            }
        }

        fn uninstall_hook(&self) {
            let mut guard = self.hook.lock().expect("lock hook runtime");
            if let Some(mut rt) = guard.take() {
                let _ = unsafe { PostThreadMessageW(rt.tid, WM_QUIT, WPARAM(0), LPARAM(0)) };
                if let Some(join) = rt.join.take() {
                    let _ = join.join();
                }
            }
        }
    }
}

#[cfg(not(target_os = "windows"))]
mod platform {
    /// 非 Windows 目标：内核驱动与 WH_KEYBOARD_LL 都不可用，后端恒为 `none`。
    #[derive(Default)]
    pub struct KeyLock;

    impl KeyLock {
        pub fn engage(&self) -> String {
            "none".to_string()
        }
        pub fn release(&self) {}
        pub fn backend(&self) -> String {
            "none".to_string()
        }
        pub fn driver_active(&self) -> bool {
            false
        }
    }
}

pub use platform::KeyLock;