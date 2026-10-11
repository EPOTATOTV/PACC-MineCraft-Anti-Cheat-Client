package com.potatotv.paccclient.redscreen;

import com.potatotv.paccclient.store.RedScreenStatePersistence;

/**
 * 红屏指令处理：解析服务端下发的 redscreen_alert / mitigation / unlock 消息，
 * 触发全屏红屏、输入暂停或解除，并同步持久化/清除红屏激活状态（供重启恢复）。
 * <p>消息结构对应 PTV 后端广播 Json：{event_type, alert_id, level, cheat_type,
 * pteid_masked, timestamp, risk_score, game_edition}。</p>
 *
 * <p>v5.2 §7.3：新增激活回调，供查端回放（{@code SessionRecorder}）在红屏瞬间开始导出录像。</p>
 */
public final class RedscreenReceiver {

    private static RedScreenStatePersistence persistence;
    private static volatile int activeLevel;
    private static volatile long activeSince;
    /** 本机玩家的真实 PTEID，用于判定告警是否指向本机；未注入时为 null。 */
    private static volatile String localPteid;
    /** 当前红屏是否针对本机玩家（决定是否锁键盘、是否显示「继续」按钮）。 */
    private static volatile boolean activeIsTarget;
    private static volatile String activeCheatType = "unknown";
    private static volatile String activeRiskScore = "0";
    /** 红屏激活回调（level, alertId）；未注册时为 null。 */
    private static volatile java.util.function.BiConsumer<Integer, String> onActivated;

    private RedscreenReceiver() {
    }

    /**
     * 注入本机玩家 PTEID（登录后调用），用于把广播告警判定为「本机被检测」还是「他机公示」。
     * 不注入时 {@link #activeIsTarget()} 保守返回 true，避免被检测者绕过锁定。
     */
    public static void setLocalPteid(String pteid) {
        localPteid = pteid;
    }

    /** 注册红屏激活回调（重复注册以最后一次为准）。 */
    public static void onActivated(java.util.function.BiConsumer<Integer, String> listener) {
        onActivated = listener;
    }

    /** 注入红屏状态持久化；未注入则不落盘（纯内存演示模式兼容）。 */
    public static void init(RedScreenStatePersistence p) {
        persistence = p;
    }

    /** 当前是否处于红屏激活态（供桌面壳轮询触发 redscreen_triggered 事件）。 */
    public static int activeLevel() {
        return activeLevel;
    }

    public static long activeSince() {
        return activeSince;
    }

    /** 当前红屏是否针对本机玩家：仅被检测者锁键盘、无「继续」按钮。 */
    public static boolean activeIsTarget() {
        return activeIsTarget;
    }

    public static String activeCheatType() {
        return activeCheatType;
    }

    public static String activeRiskScore() {
        return activeRiskScore;
    }

    /**
     * 标记红屏激活态，并按持久化/下发的脱敏 PTEID 判定是否指向本机。
     * 无法判定（未注入本机 PTEID 或脱敏值缺失）时保守视为被检测者。
     */
    public static void markActive(int level, String pteidMasked, String cheatType, String riskScore) {
        activeLevel = Math.max(1, level);
        activeSince = System.currentTimeMillis();
        activeCheatType = cheatType == null || cheatType.isBlank() ? "unknown" : cheatType;
        activeRiskScore = riskScore == null || riskScore.isBlank() ? "0" : riskScore;
        String local = localPteid;
        activeIsTarget = local == null || local.isBlank() || pteidMasked == null || pteidMasked.isBlank()
                || mask(local).equals(pteidMasked);
    }

    private static void markClear() {
        activeLevel = 0;
        activeSince = 0;
        activeIsTarget = false;
        activeCheatType = "unknown";
        activeRiskScore = "0";
    }

    /** 脱敏算法需与服务端 {@code RedscreenService.mask} 保持一致。 */
    private static String mask(String pteid) {
        if (pteid == null || pteid.length() < 4) return "****";
        return pteid.substring(0, 2) + "***" + pteid.substring(pteid.length() - 2);
    }

    /** 通知红屏激活（回调异常绝不影响红屏本身）。 */
    private static void notifyActivated(int level, String alertId) {
        java.util.function.BiConsumer<Integer, String> listener = onActivated;
        if (listener == null) return;
        try {
            listener.accept(level, alertId);
        } catch (Exception e) {
            System.err.println("[PTV-Client] 红屏回调失败（不影响红屏）: " + e.getMessage());
        }
    }

    public static void handle(String json) {
        if (json == null) return;
        try {
            if (json.contains("redscreen_alert")) {
                int level = intOf(json, "level", 2);
                String type = stringOf(json, "cheat_type", "unknown");
                String pteid = stringOf(json, "pteid_masked", "****");
                String risk = stringOf(json, "risk_score", "0");
                String alertId = stringOf(json, "alert_id", "");
                if (persistence != null) persistence.setActive(level, type, pteid, risk);
                markActive(level, pteid, type, risk);
                FullScreenRed.show(level, type, pteid, risk);
                notifyActivated(level, alertId);
            } else if (json.contains("\"type\":\"mitigation\"")) {
                // 服务端就地防护指令（如 force_close）
                System.out.println("[PTV-Client] 收到缓解指令: " + json);
            } else if (json.contains("unlock") && !json.contains("redscreen")) {
                if (persistence != null) persistence.clear();
                markClear();
                FullScreenRed.dismiss();
            }
        } catch (Exception e) {
            System.err.println("[PTV-Client] 处理红屏指令失败: " + e.getMessage());
        }
    }

    // ---- 极简 JSON 字段提取（避免引入库） ----
    private static int intOf(String json, String key, int def) {
        try {
            return Integer.parseInt(stringOf(json, key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    private static String stringOf(String json, String key, String def) {
        String marker = "\"" + key + "\":";
        int i = json.indexOf(marker);
        if (i < 0) return def;
        String rest = json.substring(i + marker.length()).trim();
        int depth = 0;
        for (int j = 0; j < rest.length(); j++) {
            char ch = rest.charAt(j);
            if (ch == '{' || ch == '[') depth++;
            else if (ch == '}' || ch == ']') depth--;
            else if (ch == ',' && depth == 0) {
                rest = rest.substring(0, j);
                break;
            }
        }
        rest = rest.trim();
        if (rest.startsWith("\"")) rest = rest.substring(1, rest.indexOf('"', 1));
        return rest;
    }
}