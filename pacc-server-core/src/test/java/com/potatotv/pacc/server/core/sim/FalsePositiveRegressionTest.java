package com.potatotv.pacc.server.core.sim;

import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.packet.PacketTypes;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-08 误报复现回归：逐条重放 {@link FalsePositiveCase} 登记的合法行为，要求零告警。
 *
 * <p>这个测试是「修误报必须带回归用例」的执行面：新增检测项时，先补登记再改代码；
 * 修复误报时，把触发场景登记成一条 case，后续 PR 由这里兜底。
 */
class FalsePositiveRegressionTest {

    @Test
    void caseIdsAreUniqueAndCoverEveryDetector() {
        List<FalsePositiveCase> cases = FalsePositiveCase.all();
        Set<String> ids = new HashSet<>();
        Set<String> covered = new HashSet<>();
        for (FalsePositiveCase c : cases) {
            assertTrue(ids.add(c.id()), "用例 ID 重复: " + c.id());
            covered.add(c.cheatType());
        }
        for (CheatModule module : CheatModule.values()) {
            if (!module.hasDetection()) {
                continue;
            }
            for (String cheatType : module.expectedCheatTypes()) {
                assertTrue(covered.contains(cheatType),
                        "检测项 " + cheatType + " 没有登记误报回归用例");
            }
        }
        assertNotNull(FalsePositiveCase.byId("FP-001"));
    }

    @Test
    void everyRegisteredCaseStaysFalsePositiveFree() {
        for (FalsePositiveCase c : FalsePositiveCase.all()) {
            SimServerAdapter adapter = new SimServerAdapter();
            DetectionEngine engine = SimEngines.fullEngine(ServerPluginConfig.defaults(), adapter);
            List<DetectionResult> hits = switch (c.kind()) {
                case MOVEMENT -> replayMovement(c, engine);
                case COMBAT -> replayCombat(c, engine);
                case PACKET -> replayPacket(c, engine);
                case INVENTORY -> replayInventory(c, engine);
            };
            assertTrue(hits.isEmpty(), c.id() + " 误报：" + c.note() + " " + hits);
            assertTrue(adapter.alerts().isEmpty(), c.id() + " 不应产生告警");
            assertTrue(adapter.punishments().isEmpty(), c.id() + " 不应产生处置");
        }
    }

    @Test
    void byIdReturnsRegisteredCaseOnly() {
        assertEquals("SPEED", FalsePositiveCase.byId("FP-001").cheatType());
        assertEquals(null, FalsePositiveCase.byId("FP-999"));
    }

    private static List<DetectionResult> replayMovement(FalsePositiveCase c, DetectionEngine engine) {
        List<LegitMoveSample> samples = new LegitTrajectoryGenerator(
                c.scenario(), "fp-" + c.id(), 20261018L, c.samples()).generate();
        for (LegitMoveSample sample : samples) {
            List<DetectionResult> results = engine.handleMove(sample.serverFrom(), sample.serverTo());
            if (!results.isEmpty()) {
                return results;
            }
        }
        return List.of();
    }

    private static List<DetectionResult> replayCombat(FalsePositiveCase c, DetectionEngine engine) {
        // 人类点击间隔带抖动（180-310ms），不是固定节拍；视角也在正前方小幅浮动
        long[] intervals = {180, 240, 310, 220, 260, 190, 300, 230};
        long ts = 0;
        for (int i = 0; i < c.samples(); i++) {
            ts += intervals[i % intervals.length];
            CombatEvent event = new CombatEvent("fp-" + c.id(), "target", new Position(0, 64, 0),
                    new Position(0, 64, 2.5), ts, false, 8.0 + (i % 5));
            List<DetectionResult> results = engine.handleCombat(event);
            if (!results.isEmpty()) {
                return results;
            }
        }
        return List.of();
    }

    private static List<DetectionResult> replayPacket(FalsePositiveCase c, DetectionEngine engine) {
        for (int i = 0; i < c.samples(); i++) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(PacketTypes.FIELD_X, Double.toString(i * 0.1));
            fields.put(PacketTypes.FIELD_Y, "64");
            fields.put(PacketTypes.FIELD_Z, "0");
            fields.put(PacketTypes.FIELD_ON_GROUND, "true");
            PacketView view = new PacketView("fp-" + c.id(), PacketTypes.PLAYER_POSITION,
                    i * 50L, fields);
            List<DetectionResult> results = engine.handlePacket(view);
            if (!results.isEmpty()) {
                return results;
            }
        }
        return List.of();
    }

    private static List<DetectionResult> replayInventory(FalsePositiveCase c, DetectionEngine engine) {
        String action = "FAST_EAT".equals(c.cheatType()) ? "FAST_EAT" : "TOTEM_SWAP";
        long interval = "FAST_EAT".equals(c.cheatType()) ? 1000L : 700L;
        for (int i = 0; i < c.samples(); i++) {
            List<DetectionResult> results = engine.handleInventory("fp-" + c.id(), action, i * interval);
            if (!results.isEmpty()) {
                return results;
            }
        }
        return List.of();
    }
}
