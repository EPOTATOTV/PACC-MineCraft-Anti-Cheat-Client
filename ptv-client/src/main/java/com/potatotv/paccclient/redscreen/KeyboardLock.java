package com.potatotv.paccclient.redscreen;

/**
 * 键盘锁「实施状态镜像」。
 * <p>红屏期间真正拦截键盘/鼠标输入的是 OS 层：Windows 内核驱动（backend=kernel）与
 * Tauri 桌面壳的用户态低级键盘钩子（backend=hook）。真实拦截不在本类实现——这是明确的
 * 职责迁移，不是「未实现」：本类只维护一份可查询状态，供 {@code GET /status} 查询，
 * 并由 Tauri 壳经 {@code POST /enforcement} 回报覆盖为真实实施后端。</p>
 * <p>默认 backend 为 {@code "java"}（无桌面壳时的 Swing 降级）；桌面壳托管时由壳回报
 * kernel/hook/none。</p>
 */
public final class KeyboardLock {

    /**
     * 键盘锁开关：排障时可关闭。读系统属性 {@code pacc.client.keyboard-lock.enabled} 或
     * 环境变量 {@code PACC_KEYBOARD_LOCK=0}，默认开启。关闭后 {@link #lock()} 变为空操作，
     * 状态查询如实返回未上锁。
     */
    private static final boolean LOCK_ENABLED = readEnabled();

    private static volatile boolean locked = false;
    private static volatile boolean driverActive = false;
    private static volatile String backend = "java";

    private KeyboardLock() {
    }

    public static boolean isLocked() {
        return locked;
    }

    public static void lock() {
        if (locked || !LOCK_ENABLED) return;  // 状态未变则不重复打印，避免刷屏
        locked = true;
        System.out.println("[PTV-Client] 输入已暂停（红屏期间禁用键盘/鼠标，管理员可远程查端）");
    }

    public static void unlock() {
        if (!locked) return;  // 状态未变则不重复打印，避免刷屏
        locked = false;
        System.out.println("[PTV-Client] 输入已恢复");
    }

    /** 当前生效的键盘锁实施后端：kernel / hook / none / java（java=无桌面壳降级）。 */
    public static String backend() {
        return backend;
    }

    /** 由 Tauri 壳经 {@code POST /enforcement} 回报真实实施后端。 */
    public static void reportBackend(String value) {
        if (value != null && !value.isBlank()) backend = value;
    }

    public static boolean isDriverActive() {
        return driverActive;
    }

    /** 由 Tauri 壳经 {@code POST /enforcement} 回报内核驱动是否处于活动状态。 */
    public static void setDriverActive(boolean active) {
        driverActive = active;
    }

    private static boolean readEnabled() {
        String v = System.getProperty("pacc.client.keyboard-lock.enabled");
        if (v == null || v.isBlank()) v = System.getenv("PACC_KEYBOARD_LOCK");
        return v == null || v.isBlank() || Boolean.parseBoolean(v);
    }
}