package com.potatotv.pacc.forge;

import com.potatotv.pacc.client.core.detection.ClientDetectionEngine;
import com.potatotv.pacc.client.core.model.ClientIdentity;
import com.potatotv.pacc.client.core.transport.PtvReporter;
import com.potatotv.pacc.forge.input.ForgeKeyboardLockController;
import com.potatotv.pacc.forge.render.ForgeRedScreenController;

/**
 * Forge 侧的客户端运行状态：持有检测引擎、红屏控制器与输入锁控制器。
 *
 * <p>上报地址、密钥与 PTEID 通过系统属性或环境变量注入；缺省时不建立上报通道（本地检测照常跑）。
 */
public final class PaccClientState {

    public static final String CLIENT_VERSION = "6.0.0";

    private static final String PTV_BASE_URL = readConfig("pacc.ptv.baseUrl", "PACC_PTV_BASE_URL");
    private static final String PTV_SECRET = readConfig("pacc.ptv.secret", "PACC_PTV_SECRET");
    private static final String PTEID = readConfig("pacc.pteid", "PACC_PTEID");

    private static volatile PaccClientState instance;

    private final ForgeRedScreenController redScreen = new ForgeRedScreenController();
    private final ForgeKeyboardLockController keyboardLock = new ForgeKeyboardLockController();

    private String currentPlayer = "";
    private ClientDetectionEngine engine;

    private PaccClientState() {
    }

    public static PaccClientState get() {
        PaccClientState local = instance;
        if (local == null) {
            synchronized (PaccClientState.class) {
                local = instance;
                if (local == null) {
                    local = new PaccClientState();
                    instance = local;
                }
            }
        }
        return local;
    }

    public ForgeRedScreenController redScreen() {
        return redScreen;
    }

    public ForgeKeyboardLockController keyboardLock() {
        return keyboardLock;
    }

    public String clientVersion() {
        return CLIENT_VERSION;
    }

    /** 取（必要时创建）当前本地玩家的检测引擎；玩家切换时重建并重置历史。 */
    public ClientDetectionEngine engineFor(String playerName) {
        String name = playerName == null ? "" : playerName;
        if (engine == null || !name.equals(currentPlayer)) {
            currentPlayer = name;
            ClientIdentity identity = new ClientIdentity(pteidOrUnknown(), name, CLIENT_VERSION);
            PtvReporter reporter = new PtvReporter(PTV_BASE_URL, PTV_SECRET);
            engine = new ClientDetectionEngine(identity, reporter::report, redScreen, keyboardLock);
        }
        return engine;
    }

    /** 红屏到时自动恢复输入；由客户端 tick 调用。 */
    public void tickRedScreenExpiry() {
        if (redScreen.expireIfNeeded()) {
            keyboardLock.unlock();
        }
    }

    private static String pteidOrUnknown() {
        return PTEID == null || PTEID.isBlank() ? "UNKNOWN" : PTEID;
    }

    private static String readConfig(String property, String environmentVariable) {
        String value = System.getProperty(property);
        return value == null ? System.getenv(environmentVariable) : value;
    }
}
