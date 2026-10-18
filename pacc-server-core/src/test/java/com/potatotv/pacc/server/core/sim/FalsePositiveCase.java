package com.potatotv.pacc.server.core.sim;

import java.util.List;

/**
 * 误报复现回归用例登记（P-08）。
 *
 * <p>按 SpigotMC 社区讨论的口径：每个误报修复都要能在回归库里找到对应用例。
 * 这里登记的是「合法行为 + 期望不触发」的最小单元；用例按检测通道分成移动 / 战斗 / 数据包 / 背包四类，
 * 由 {@code FalsePositiveRegressionTest} 逐条重放。新增检测项或修误报时，先在这里登记用例再改代码。
 *
 * @param id        用例 ID（稳定，PR 描述里引用它）
 * @param cheatType 期望不触发的检测项（与 DetectionResult.cheatType 一致）
 * @param kind      重放通道
 * @param scenario  移动类用例的合法行为场景；非移动类为 {@code null}
 * @param samples   重放样本数
 * @param note      触发背景 / 回归原因
 */
record FalsePositiveCase(
        String id,
        String cheatType,
        Kind kind,
        SimScenario scenario,
        int samples,
        String note) {

    enum Kind {
        MOVEMENT,
        COMBAT,
        PACKET,
        INVENTORY
    }

    static FalsePositiveCase movement(String id, String cheatType, SimScenario scenario, String note) {
        return new FalsePositiveCase(id, cheatType, Kind.MOVEMENT, scenario, 240, note);
    }

    static FalsePositiveCase combat(String id, String cheatType, String note) {
        return new FalsePositiveCase(id, cheatType, Kind.COMBAT, null, 24, note);
    }

    static FalsePositiveCase packet(String id, String cheatType, String note) {
        return new FalsePositiveCase(id, cheatType, Kind.PACKET, null, 40, note);
    }

    static FalsePositiveCase inventory(String id, String cheatType, String note) {
        return new FalsePositiveCase(id, cheatType, Kind.INVENTORY, null, 12, note);
    }

    /** 当前登记的回归用例。 */
    static List<FalsePositiveCase> all() {
        return List.of(
                movement("FP-001", "SPEED", SimScenario.WALK, "普通走动被误判为 SpeedHack"),
                movement("FP-002", "FLY", SimScenario.JUMP, "连续跳跃被误判为悬空"),
                movement("FP-003", "SPEED", SimScenario.SPRINT, "疾跑终端速度贴近阈值"),
                movement("FP-004", "SPEED", SimScenario.SNEAK, "潜行移动与地面摩擦"),
                movement("FP-005", "NO_FALL", SimScenario.SWIM, "游泳下落被误判为落地未受伤"),
                movement("FP-006", "SCAFFOLD", SimScenario.BRIDGE, "搭桥时边退边放方块"),
                movement("FP-007", "SPEED", SimScenario.HIGH_LATENCY, "高延迟下移动包成批到达"),
                movement("FP-008", "packet-timing", SimScenario.BOAT, "乘船时包节奏与步行不同"),
                movement("FP-009", "prediction-engine", SimScenario.ELYTRA, "鞘翅滑翔的物理分支"),
                combat("FP-010", "REACH", "正常 2.5 格攻击距离"),
                combat("FP-011", "AUTO_CLICKER", "4 CPS 正常点击"),
                combat("FP-012", "KILL_AURA", "同一目标连续攻击且视角在正前方"),
                inventory("FP-013", "FAST_EAT", "正常进食间隔 1 秒"),
                inventory("FP-014", "AUTO_TOTEM", "正常图腾切换间隔 700ms"),
                packet("FP-015", "packet-timing", "稳定 50ms 移动包节奏")
        );
    }

    /** 按 ID 取用例；未登记返回 {@code null}。 */
    static FalsePositiveCase byId(String id) {
        return all().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }
}
