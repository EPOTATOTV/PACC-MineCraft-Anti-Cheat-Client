package com.potatotv.pacc.server.core.laplace;

import com.potatotv.pacc.server.core.ServerPlatformAdapter;
import com.potatotv.pacc.server.core.check.PacketCheck;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.model.ServerType;
import com.potatotv.pacc.server.core.report.NoopPtvServerReporter;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaplaceTargetTest {

    @Test
    void descriptorDeclaresAtLeastTenEventEntries() {
        LaplaceTargetDescriptor descriptor = LaplaceTargetDescriptor.builtin(ServerPluginConfig.defaults());
        assertTrue(descriptor.eventClasses().size() >= 10,
                "拉普拉斯 14.8 验收要求事件桥至少归一 10 类事件");
        assertEquals(LaplaceTargetDescriptor.TARGET_ID, descriptor.targetId());
        assertEquals(LaplaceTargetDescriptor.KIND, descriptor.kind());
        assertEquals(LaplaceTargetDescriptor.CONTRACT_VERSION, descriptor.contractVersion());
    }

    @Test
    void descriptorProbeCapabilitiesDoNotOverclaimClientScans() {
        Map<String, Boolean> capabilities = LaplaceTargetDescriptor.defaultProbeCapabilities();
        assertTrue(capabilities.get("server_packet_stream"));
        assertFalse(capabilities.get("client_process_scan"),
                "客户端探针由 ptv-client 经 PTV 上报，不能在本目标里声称支持");
        assertFalse(capabilities.get("client_registry_scan"));
    }

    @Test
    void thresholdAnchorsFollowEffectiveConfig() {
        ServerPluginConfig custom = ServerPluginConfig.builder()
                .maxReachDistance(4.5)
                .maxCps(22)
                .confidenceThreshold(75)
                .build();
        Map<String, Double> anchors = LaplaceTargetDescriptor.thresholdAnchors(custom);
        assertEquals(4.5, anchors.get("combat.max-reach-distance"), 1e-9);
        assertEquals(22.0, anchors.get("combat.max-cps"), 1e-9);
        assertEquals(75.0, anchors.get("inventory.confidence-threshold"), 1e-9);
        // 包级阈值不来自配置，必须与真实常量一致，否则平台侧注入幅度会算偏
        assertEquals(10.0, anchors.get("packet.teleport-threshold"), 1e-9);
        assertEquals(3.0, anchors.get("packet.no-fall-min-drop"), 1e-9);
        assertEquals(1.005, anchors.get("packet.timer-threshold"), 1e-9);
    }

    @Test
    void shippedDescriptorMatchesGeneratedJson() throws Exception {
        LaplaceTargetDescriptor descriptor = LaplaceTargetDescriptor.builtin(ServerPluginConfig.defaults());
        String generated = normalize(descriptor.toJson());
        String shipped;
        try (InputStream in = getClass().getResourceAsStream("/pacc-laplace-target.json")) {
            assertNotNull(in, "pacc-laplace-target.json 必须随 jar 发布");
            shipped = normalize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(shipped, generated,
                "resources/pacc-laplace-target.json 与代码生成的描述符漂移了，改常量时记得同步文件");
    }

    @Test
    void flagIdIsStableAndTierDefaults() {
        LaplaceFlag flag = LaplaceFlag.from(com.potatotv.pacc.server.core.model.DetectionResult.violation(
                com.potatotv.pacc.server.core.model.CheckType.PACKET,
                "NO_FALL", 70, "落地前竖向落差 10.00 块", 1000L));
        assertEquals("packet:no_fall", flag.flagId());
        assertEquals(LaplaceFlag.DEFAULT_TIER, flag.tier());
        assertEquals("", flag.evidenceRef());
    }

    @Test
    void evidenceRefIsExtractedFromDetail() {
        LaplaceFlag flag = LaplaceFlag.from(com.potatotv.pacc.server.core.model.DetectionResult.violation(
                com.potatotv.pacc.server.core.model.CheckType.PACKET,
                "fusion-flag", 90, "多层证据融合: x evidenceMac=abc123 tail", 1000L));
        assertEquals("abc123", flag.evidenceRef());
    }

    @Test
    void facadeExposesFlagsForRealPacketFlow() {
        ServerPluginConfig config = ServerPluginConfig.defaults();
        List<ServerCheck> checks = List.of(new PacketCheck());
        LaplaceTargetFacade facade = new LaplaceTargetFacade(
                config, new StubAdapter(), checks, NoopPtvServerReporter.INSTANCE);

        facade.onPacket(packet("p1", 1000, 0, 64, 0));
        List<LaplaceFlag> flags = facade.onPacket(packet("p1", 1050, 30, 64, 0));
        assertTrue(flags.stream().anyMatch(f -> "packet:fast_move".equals(f.flagId())));
        facade.reset("p1");
        assertTrue(facade.onPacket(packet("p1", 1100, 0, 64, 0)).isEmpty());
    }

    @Test
    void facadeTickAndDescriptorWork() {
        LaplaceTargetFacade facade = new LaplaceTargetFacade(
                ServerPluginConfig.defaults(), new StubAdapter(), List.of(), NoopPtvServerReporter.INSTANCE);
        facade.onTick(1000L);
        facade.onTick(1050L);
        assertTrue(facade.descriptor().thresholdAnchors().containsKey("packet.timer-threshold"));
        assertTrue(facade.onMove(position(1000), position(1000)).isEmpty());
    }

    /** 去掉全部空白后比较，避免文件缩进与生成器格式差异造成假失败。 */
    private static String normalize(String json) {
        return json.replaceAll("\\s+", "");
    }

    private static PacketView packet(String playerId, long ts, double x, double y, double z) {
        return new PacketView(playerId, "PLAYER_POSITION", ts,
                Map.of("x", Double.toString(x), "y", Double.toString(y), "z", Double.toString(z)));
    }

    private static PlayerPosition position(long ts) {
        return new PlayerPosition("p1", new Position(0, 64, 0), 0f, 0f, ts, true, GameMode.SURVIVAL);
    }

    /** 最小适配层：本测试只验证门面转发，不关心平台行为。 */
    private static final class StubAdapter implements ServerPlatformAdapter {

        @Override
        public ServerType serverType() {
            return ServerType.PAPER;
        }

        @Override
        public String serverId() {
            return "test";
        }

        @Override
        public String serverName() {
            return "test";
        }

        @Override
        public int maxPlayers() {
            return 20;
        }

        @Override
        public String serverVersion() {
            return "1.21";
        }

        @Override
        public PlayerPosition getPlayerPosition(String playerId) {
            return null;
        }

        @Override
        public GameMode getGameMode(String playerId) {
            return GameMode.SURVIVAL;
        }

        @Override
        public String getClientBrand(String playerId) {
            return "vanilla";
        }

        @Override
        public List<String> getInstalledMods(String playerId) {
            return List.of();
        }

        @Override
        public List<AddonInfo> getInstalledAddons(String playerId) {
            return List.of();
        }

        @Override
        public void alert(String playerId, String message) {
            // 测试不关心告警落地
        }

        @Override
        public void punish(String playerId, PunishmentType type, String reason, long durationMs) {
            // 默认仅告警，不会走到这里
        }
    }
}
