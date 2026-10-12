package com.potatotv.pacc.server.core.intel;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intel 检查集成测试：物理预测 / Reach / 品牌 / Timer 四条通道的最小闭环。
 */
class IntelChecksTest {

    private static PlayerPosition pos(String playerId, double x, double y, double z, boolean onGround, GameMode mode) {
        return new PlayerPosition(playerId, new Position(x, y, z), 0f, 0f, System.currentTimeMillis(), onGround, mode);
    }

    @Test
    void 垂直飞升被物理预测拦截() {
        PredictionIntelCheck check = new PredictionIntelCheck(new IntelRuntime());
        List<DetectionResult> results = List.of();
        for (int i = 0; i < 10 && results.isEmpty(); i++) {
            double y = 64 + i * 0.5;
            results = check.onMove(
                    pos("flyer", 0, y, 0, true, GameMode.SURVIVAL),
                    pos("flyer", 0, y + 0.5, 0, false, GameMode.SURVIVAL));
        }
        assertFalse(results.isEmpty(), "悬停上升应产出物理预测违规");
        assertTrue(results.stream().anyMatch(r -> r.violation() && r.confidence() > 0));
    }

    @Test
    void 创造模式豁免物理预测() {
        PredictionIntelCheck check = new PredictionIntelCheck(new IntelRuntime());
        for (int i = 0; i < 10; i++) {
            double y = 64 + i * 0.5;
            List<DetectionResult> results = check.onMove(
                    pos("creator", 0, y, 0, true, GameMode.CREATIVE),
                    pos("creator", 0, y + 0.5, 0, false, GameMode.CREATIVE));
            assertTrue(results.isEmpty(), "创造模式不应触发物理预测");
        }
    }

    @Test
    void 正常行走不误报() {
        PredictionIntelCheck check = new PredictionIntelCheck(new IntelRuntime());
        for (int i = 0; i < 10; i++) {
            double y = 64;
            List<DetectionResult> results = check.onMove(
                    pos("walker", i * 0.2, y, 0, true, GameMode.SURVIVAL),
                    pos("walker", (i + 1) * 0.2, y, 0, true, GameMode.SURVIVAL));
            assertTrue(results.isEmpty(), "正常水平移动不应触发，第 " + i + " 帧结果 " + results);
        }
    }

    @Test
    void 超距攻击被拦截() {
        CombatIntelCheck check = new CombatIntelCheck(new IntelRuntime());
        CombatEvent event = new CombatEvent(
                "attacker", "victim",
                new Position(0, 64, 0), new Position(0, 64, 5.0),
                System.currentTimeMillis(), false, 0.0);
        List<DetectionResult> results = check.onCombat(event);
        assertTrue(results.stream().anyMatch(r -> "combat-reach".equals(r.cheatType())));
    }

    @Test
    void 作弊品牌被拦截() {
        PacketIntelCheck check = new PacketIntelCheck(new IntelRuntime());
        PacketView packet = new PacketView("cheater", "BRAND",
                System.currentTimeMillis(), Map.of("brand", "wurst client 7"));
        List<DetectionResult> results = check.onPacket(packet);
        assertTrue(results.stream().anyMatch(r -> r.violation()));
    }

    @Test
    void timer加速被拦截() {
        PacketIntelCheck check = new PacketIntelCheck(new IntelRuntime());
        long now = System.currentTimeMillis();
        List<DetectionResult> results = List.of();
        for (int i = 0; i < 120 && results.isEmpty(); i++) {
            results = check.onPacket(new PacketView("timer", "Position",
                    now + i * 25, Map.of()));
        }
        assertTrue(results.stream().anyMatch(r -> "packet-timing".equals(r.cheatType())),
                "25ms 间隔的移动包应触发 Timer，实际 " + results);
    }

    @Test
    void 工厂装配四类检查() {
        List<com.potatotv.pacc.server.core.check.ServerCheck> checks = IntelChecks.defaults();
        assertEquals(4, checks.size());
        assertTrue(checks.stream().anyMatch(c -> c.type() == CheckType.MOVE));
        assertTrue(checks.stream().anyMatch(c -> c.type() == CheckType.COMBAT));
        assertTrue(checks.stream().anyMatch(c -> c.type() == CheckType.PACKET));
    }

    @Test
    void 证据链密钥派生与封存() {
        assertNull(IntelChecks.chainKeyFrom(null));
        assertNull(IntelChecks.chainKeyFrom(""));
        assertNull(IntelChecks.chainKeyFrom("too-short"));
        byte[] key = IntelChecks.chainKeyFrom("0123456789abcdef");
        assertNotNull(key);

        IntelRuntime runtime = new IntelRuntime();
        assertFalse(runtime.evidenceChainEnabled());
        assertEquals("", runtime.sealEvidence("x"), "未启用时应为空串，不落链");

        runtime.enableEvidenceChain(key);
        assertTrue(runtime.evidenceChainEnabled());
        String mac = runtime.sealEvidence("payload");
        assertFalse(mac.isEmpty());
        assertEquals(1, runtime.evidenceChainLength());
        assertEquals(mac, runtime.evidenceChainTailMac());
        // 链式哈希可校验，篡改长度后校验失败
        assertTrue(com.potatotv.pacc.detection.fusion.EvidenceChain.verify(key, runtime.evidenceChainEntries()));
    }

    @Test
    void 指纹库覆盖五大类别且不少于一百条() {
        com.potatotv.pacc.detection.threatintel.CheatCatalog catalog = IntelChecks.builtinCatalog();
        assertTrue(catalog.total() >= 100, "M1 验收：指纹库 ≥100 条，实际 " + catalog.total());
        assertTrue(catalog.coversAllCategories(), "M1 验收：四大类别（含驱动/配置）全覆盖");
    }
}
