package com.potatotv.pacc.server.core.sim;

import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * T3 作弊注入器：对每个模块，基于事件流注入超过阈值的违规行为，喂给 server-core 的
 * {@code DetectionEngine}，返回检测结果列表。
 *
 * <p>注入幅度来自 {@link CheatSpec#magnitude()}（阈值 × 相对档位系数），保证越过阈值。
 * 未实现检测项的模块（phase / jesus）注入正常速度的行为，用于登记而非计算检出率。
 */
final class CheatInjector {

    private CheatInjector() {
    }

    static List<DetectionResult> inject(DetectionEngine engine, CheatSpec spec) {
        String pid = spec.playerId();
        double magnitude = spec.magnitude();
        List<DetectionResult> results = new ArrayList<>();
        switch (spec.module()) {
            case SPEED -> results.addAll(engine.handleMove(
                    pos(pid, 0, 64, 0, 1000, true),
                    pos(pid, magnitude, 64, 0, 1050, true)));
            case FLY -> results.addAll(engine.handleMove(
                    pos(pid, 0, 64, 0, 1000, true),
                    pos(pid, 0, 64 + magnitude, 0, 1050, false)));
            case REACH -> results.addAll(engine.handleCombat(new CombatEvent(
                    pid, "target", pos(0, 64, 0), pos(magnitude, 64, 0), 1000, false, 0.0)));
            case AUTO_CLICKER -> {
                int clicks = (int) Math.round(magnitude);
                for (int i = 0; i < clicks; i++) {
                    results.addAll(engine.handleCombat(new CombatEvent(
                            pid, "target", pos(0, 64, 0), pos(1, 64, 0), 1000 + i * 10L, false, 0.0)));
                }
            }
            case KILL_AURA -> {
                int switches = (int) Math.round(magnitude);
                for (int i = 0; i < switches; i++) {
                    results.addAll(engine.handleCombat(new CombatEvent(
                            pid, "target-" + i, pos(0, 64, 0), pos(1, 64, 0), 2000 + i * 10L, false, 0.0)));
                }
            }
            case TIMER -> {
                long interval = (long) Math.round(magnitude);
                long ts = 0;
                for (int i = 0; i < 22; i++) {
                    results.addAll(engine.handlePacket(new PacketView(pid, "PLAYER_POSITION", ts, Map.of())));
                    ts += interval;
                }
            }
            case BLINK -> {
                long ts = 0;
                for (int i = 0; i < 20; i++) {
                    results.addAll(engine.handlePacket(new PacketView(pid, "PLAYER_POSITION", ts, Map.of())));
                    ts += 50;
                }
                ts += (long) Math.round(magnitude);
                results.addAll(engine.handlePacket(new PacketView(pid, "PLAYER_POSITION", ts, Map.of())));
            }
            case NO_FALL -> results.addAll(engine.handlePacket(new PacketView(pid, "PLAYER_GROUND", 1000,
                    Map.of("y", "50", "deltaY", String.valueOf(-magnitude)))));
            case SCAFFOLD -> {
                int stacks = (int) Math.round(magnitude);
                long ts = 1000;
                double y = 65;
                for (int i = 0; i < stacks; i++) {
                    results.addAll(engine.handleInteract(pid, pos(0, y, 0), "PLACE", ts));
                    y -= 1;
                    ts += 60;
                }
            }
            case FAST_EAT -> {
                results.addAll(engine.handleInventory(pid, "FAST_EAT", 1000));
                results.addAll(engine.handleInventory(pid, "FAST_EAT", 1000 + (long) Math.round(magnitude)));
            }
            case AUTO_TOTEM -> {
                results.addAll(engine.handleInventory(pid, "TOTEM_SWAP", 1000));
                results.addAll(engine.handleInventory(pid, "TOTEM_SWAP", 1000 + (long) Math.round(magnitude)));
            }
            // 未实现检测项：注入正常速度行为（无对应检测通道）
            // 两个 case 分开写：CodeQL 的 missing-case-in-switch 对多标签 arrow case 会误报缺 JESUS 分支。
            case PHASE -> results.addAll(engine.handleMove(
                    pos(pid, 0, 64, 0, 1000, true),
                    pos(pid, magnitude, 64, 0, 1050, true)));
            case JESUS -> results.addAll(engine.handleMove(
                    pos(pid, 0, 64, 0, 1000, true),
                    pos(pid, magnitude, 64, 0, 1050, true)));
        }
        return results;
    }

    private static Position pos(double x, double y, double z) {
        return new Position(x, y, z);
    }

    private static PlayerPosition pos(String playerId, double x, double y, double z, long ts, boolean onGround) {
        return new PlayerPosition(playerId, new Position(x, y, z), 0f, 0f, ts, onGround, GameMode.SURVIVAL);
    }
}
