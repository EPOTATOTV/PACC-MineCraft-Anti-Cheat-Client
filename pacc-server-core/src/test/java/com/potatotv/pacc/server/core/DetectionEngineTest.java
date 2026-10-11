package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerInfo;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.model.ServerType;
import com.potatotv.pacc.server.core.report.PtvServerReporter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionEngineTest {

    @Test
    void disabledCheckTypeIsSkipped() {
        FakeAdapter adapter = new FakeAdapter();
        FakeReporter reporter = new FakeReporter();
        ServerPluginConfig config = ServerPluginConfig.builder()
                .enabledChecks(Set.of(CheckType.COMBAT))
                .build();
        DetectionEngine engine = new DetectionEngine(
                config, adapter, List.of(moveViolationCheck(90)), reporter);

        List<DetectionResult> results = engine.handleMove(player("p"), player("p"));

        assertTrue(results.isEmpty());
        assertTrue(adapter.alerts.isEmpty());
        assertTrue(reporter.events.isEmpty());
    }

    @Test
    void defaultModeAlertsButNeverPunishes() {
        FakeAdapter adapter = new FakeAdapter();
        FakeReporter reporter = new FakeReporter();
        DetectionEngine engine = new DetectionEngine(
                ServerPluginConfig.defaults(), adapter, List.of(moveViolationCheck(90)), reporter);

        List<DetectionResult> results = engine.handleMove(player("p"), player("p"));

        assertEquals(1, results.size());
        assertEquals(1, adapter.alerts.size());
        assertTrue(adapter.punishments.isEmpty());
        assertEquals(1, reporter.events.size());
        assertTrue(reporter.punishments.isEmpty());
    }

    @Test
    void modeTwoPunishesWithBanAboveThreshold() {
        FakeAdapter adapter = new FakeAdapter();
        FakeReporter reporter = new FakeReporter();
        ServerPluginConfig config = ServerPluginConfig.builder().punishmentMode(2).build();
        DetectionEngine engine = new DetectionEngine(
                config, adapter, List.of(moveViolationCheck(90)), reporter);

        engine.handleMove(player("p"), player("p"));

        assertEquals(List.of("BAN"), adapter.punishments);
        assertEquals(List.of("BAN"), reporter.punishments);
        assertEquals(1, adapter.alerts.size());
    }

    @Test
    void modeOnePunishesWithKickAboveThreshold() {
        FakeAdapter adapter = new FakeAdapter();
        FakeReporter reporter = new FakeReporter();
        ServerPluginConfig config = ServerPluginConfig.builder().punishmentMode(1).build();
        DetectionEngine engine = new DetectionEngine(
                config, adapter, List.of(moveViolationCheck(90)), reporter);

        engine.handleMove(player("p"), player("p"));

        assertEquals(List.of("KICK"), adapter.punishments);
        assertEquals(List.of("KICK"), reporter.punishments);
        assertEquals(1, adapter.alerts.size());
    }

    @Test
    void modeTwoBelowThresholdDoesNotPunish() {
        FakeAdapter adapter = new FakeAdapter();
        FakeReporter reporter = new FakeReporter();
        ServerPluginConfig config = ServerPluginConfig.builder().punishmentMode(2).build();
        DetectionEngine engine = new DetectionEngine(
                config, adapter, List.of(moveViolationCheck(30)), reporter);

        engine.handleMove(player("p"), player("p"));

        assertTrue(adapter.punishments.isEmpty());
        assertTrue(reporter.punishments.isEmpty());
        assertEquals(1, reporter.events.size());
    }

    @Test
    void belowThresholdOnlyReportsNoAlert() {
        FakeAdapter adapter = new FakeAdapter();
        FakeReporter reporter = new FakeReporter();
        DetectionEngine engine = new DetectionEngine(
                ServerPluginConfig.defaults(), adapter, List.of(moveViolationCheck(30)), reporter);

        engine.handleMove(player("p"), player("p"));

        assertEquals(1, reporter.events.size());
        assertTrue(adapter.alerts.isEmpty());
        assertTrue(adapter.punishments.isEmpty());
    }

    @Test
    void reporterExceptionDoesNotPropagate() {
        FakeAdapter adapter = new FakeAdapter();
        FakeReporter reporter = new FakeReporter();
        reporter.throwOnReport = true;
        DetectionEngine engine = new DetectionEngine(
                ServerPluginConfig.defaults(), adapter, List.of(moveViolationCheck(90)), reporter);

        assertDoesNotThrow(() -> engine.handleMove(player("p"), player("p")));
        assertEquals(1, adapter.alerts.size());
    }

    private static ServerCheck moveViolationCheck(int confidence) {
        return new ServerCheck() {
            @Override
            public CheckType type() {
                return CheckType.MOVE;
            }

            @Override
            public List<DetectionResult> onMove(PlayerPosition from, PlayerPosition to) {
                return List.of(DetectionResult.violation(
                        CheckType.MOVE, "SPEED", confidence, "test", 1234L));
            }
        };
    }

    private static PlayerPosition player(String id) {
        return new PlayerPosition(id, new Position(0, 64, 0), 0f, 0f, 1000L, true, GameMode.SURVIVAL);
    }

    private static final class FakeAdapter implements ServerPlatformAdapter {

        private final List<String> alerts = new ArrayList<>();
        private final List<String> punishments = new ArrayList<>();

        @Override
        public ServerType serverType() {
            return ServerType.PAPER;
        }

        @Override
        public String serverId() {
            return "srv-test";
        }

        @Override
        public String serverName() {
            return "Test Server";
        }

        @Override
        public int maxPlayers() {
            return 100;
        }

        @Override
        public String serverVersion() {
            return "1.21";
        }

        @Override
        public PlayerPosition getPlayerPosition(String playerId) {
            return player(playerId);
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
            alerts.add(message);
        }

        @Override
        public void punish(String playerId, PunishmentType type, String reason, long durationMs) {
            punishments.add(type.name());
        }
    }

    private static final class FakeReporter implements PtvServerReporter {

        private final List<DetectionEvent> events = new ArrayList<>();
        private final List<String> punishments = new ArrayList<>();
        private boolean throwOnReport;

        @Override
        public void register(ServerInfo info) {
            // 测试不需要
        }

        @Override
        public void report(DetectionEvent event) {
            if (throwOnReport) {
                throw new IllegalStateException("reporter down");
            }
            events.add(event);
        }

        @Override
        public void reportPunishment(
                String serverId, String playerUuid, PunishmentType type, String reason, long durationMs) {
            punishments.add(type.name());
        }
    }
}
