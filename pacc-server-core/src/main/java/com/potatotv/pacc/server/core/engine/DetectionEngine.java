package com.potatotv.pacc.server.core.engine;

import com.potatotv.pacc.server.core.ServerPlatformAdapter;
import com.potatotv.pacc.server.core.check.MoveAware;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.check.TickAware;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.report.PtvServerReporter;

import java.lang.System.Logger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 检测引擎：把平台事件分发给检查项，聚合结果，按配置决定处置动作并上报。
 *
 * <p>默认只告警。只有 {@link ServerPluginConfig#maxPunishment()} 不是 {@code ALERT} 时才会调用适配层的
 * {@code punish}。上报失败不会打断检测流程。
 */
public final class DetectionEngine {

    private static final Logger LOG = System.getLogger(DetectionEngine.class.getName());
    private static final long TEMP_BAN_DURATION_MS = 86_400_000L;

    private final ServerPluginConfig config;
    private final ServerPlatformAdapter adapter;
    private final PtvServerReporter reporter;
    private final Map<CheckType, List<ServerCheck>> checksByType;

    public DetectionEngine(ServerPluginConfig config, ServerPlatformAdapter adapter,
                           List<ServerCheck> checks, PtvServerReporter reporter) {
        this.config = Objects.requireNonNull(config, "config");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        Map<CheckType, List<ServerCheck>> map = new EnumMap<>(CheckType.class);
        if (checks != null) {
            for (ServerCheck check : checks) {
                map.computeIfAbsent(check.type(), unused -> new ArrayList<>()).add(check);
            }
        }
        this.checksByType = map;
    }

    public List<DetectionResult> handleMove(PlayerPosition from, PlayerPosition to) {
        if (to == null) {
            return List.of();
        }
        // 跨类型观察口（如 X-Ray 的探索距离统计）先拿到移动流，再走正常的 MOVE 分发
        for (List<ServerCheck> checks : checksByType.values()) {
            for (ServerCheck check : checks) {
                if (check instanceof MoveAware moveAware) {
                    moveAware.onMoveObserved(from, to);
                }
            }
        }
        return dispatch(CheckType.MOVE, to.playerId(), () -> {
            List<DetectionResult> raw = new ArrayList<>();
            for (ServerCheck check : checksFor(CheckType.MOVE)) {
                raw.addAll(check.onMove(from, to));
            }
            return raw;
        });
    }

    public List<DetectionResult> handleCombat(CombatEvent event) {
        if (event == null) {
            return List.of();
        }
        return dispatch(CheckType.COMBAT, event.attackerId(), () -> {
            List<DetectionResult> raw = new ArrayList<>();
            for (ServerCheck check : checksFor(CheckType.COMBAT)) {
                raw.addAll(check.onCombat(event));
            }
            return raw;
        });
    }

    public List<DetectionResult> handlePacket(PacketView packet) {
        if (packet == null) {
            return List.of();
        }
        return dispatch(CheckType.PACKET, packet.playerId(), () -> {
            List<DetectionResult> raw = new ArrayList<>();
            for (ServerCheck check : checksFor(CheckType.PACKET)) {
                raw.addAll(check.onPacket(packet));
            }
            return raw;
        });
    }

    public List<DetectionResult> handleInventory(String playerId, String action, long timestampMs) {
        return dispatch(CheckType.INVENTORY, playerId, () -> {
            List<DetectionResult> raw = new ArrayList<>();
            for (ServerCheck check : checksFor(CheckType.INVENTORY)) {
                raw.addAll(check.onInventory(playerId, action, timestampMs));
            }
            return raw;
        });
    }

    public List<DetectionResult> handleInteract(String playerId, Position pos, String action, long timestampMs) {
        return dispatch(CheckType.INTERACTION, playerId, () -> {
            List<DetectionResult> raw = new ArrayList<>();
            for (ServerCheck check : checksFor(CheckType.INTERACTION)) {
                raw.addAll(check.onInteract(playerId, pos, action, timestampMs));
            }
            return raw;
        });
    }

    public List<DetectionResult> handleClientBrand(String playerId, String brand) {
        if (!config.isEnabled(CheckType.CLIENT_BRAND)) {
            return List.of();
        }
        List<String> mods = safeMods(playerId);
        List<String> modHashes = safeModHashes(playerId);
        List<DetectionResult> raw = new ArrayList<>();
        for (ServerCheck check : checksFor(CheckType.CLIENT_BRAND)) {
            raw.addAll(check.onClientBrand(playerId, brand));
            raw.addAll(check.onMods(playerId, mods));
            raw.addAll(check.onModHashes(playerId, modHashes));
        }
        return finish(CheckType.CLIENT_BRAND, playerId, raw);
    }

    /**
     * 直接喂入已解析的 Mod 文件哈希（{@code pacc:mods} 通道由适配层解析时用）。
     *
     * <p>与 {@link #handleClientBrand} 的区别：那条路径会再向适配层要一遍品牌与 Mod 列表，
     * 适合事件驱动平台；平台侧已经拿到哈希清单时走这里，避免重复取数。
     */
    public List<DetectionResult> handleModHashes(String playerId, List<String> modHashes) {
        if (!config.isEnabled(CheckType.CLIENT_BRAND)) {
            return List.of();
        }
        List<String> hashes = modHashes == null ? List.of() : modHashes;
        List<DetectionResult> raw = new ArrayList<>();
        for (ServerCheck check : checksFor(CheckType.CLIENT_BRAND)) {
            raw.addAll(check.onModHashes(playerId, hashes));
        }
        return finish(CheckType.CLIENT_BRAND, playerId, raw);
    }

    public List<DetectionResult> handleAddons(String playerId, List<AddonInfo> addons) {
        return dispatch(CheckType.ADDON, playerId, () -> {
            List<DetectionResult> raw = new ArrayList<>();
            for (ServerCheck check : checksFor(CheckType.ADDON)) {
                raw.addAll(check.onAddons(playerId, addons));
            }
            return raw;
        });
    }

    /**
     * 方块被破坏（服务端认账）。X-Ray 统计与 nuker 类判定都从这里进，走 INTERACTION 分类。
     *
     * @param blockType 平台给的方块材质名；拿不到时传 {@code null}，检查项会自行跳过
     */
    public List<DetectionResult> handleBlockBreak(
            String playerId, String blockType, Position pos, long timestampMs) {
        return dispatch(CheckType.INTERACTION, playerId, () -> {
            List<DetectionResult> raw = new ArrayList<>();
            for (ServerCheck check : checksFor(CheckType.INTERACTION)) {
                raw.addAll(check.onBlockBreak(playerId, blockType, pos, timestampMs));
            }
            return raw;
        });
    }

    public List<DetectionResult> handleDataConsistency(
            String playerId, Map<String, String> clientData, Map<String, String> serverData) {
        return dispatch(CheckType.DATA_CONSISTENCY, playerId, () -> {
            List<DetectionResult> raw = new ArrayList<>();
            for (ServerCheck check : checksFor(CheckType.DATA_CONSISTENCY)) {
                raw.addAll(check.onDataConsistency(playerId, clientData, serverData));
            }
            return raw;
        });
    }

    /** 玩家下线时调用，重置所有检查项里该玩家的历史状态。 */
    public void resetPlayer(String playerId) {
        for (List<ServerCheck> checks : checksByType.values()) {
            for (ServerCheck check : checks) {
                check.reset(playerId);
            }
        }
    }

    /**
     * 服务端刻边界通知。平台适配层每刻调一次，转给需要刻信息的检查项（{@link TickAware}）。
     *
     * <p>不产出检测结果：它只是喂数据（实测刻长），所以不需要走 {@code dispatch} 的开关判断——
     * 关掉某个检查项不该让性能读数停摆。
     */
    public void handleTick(long nowMs) {
        for (List<ServerCheck> checks : checksByType.values()) {
            for (ServerCheck check : checks) {
                if (check instanceof TickAware tickAware) {
                    tickAware.onTick(nowMs);
                }
            }
        }
    }

    private List<DetectionResult> dispatch(
            CheckType type, String playerId, Supplier<List<DetectionResult>> run) {
        if (!config.isEnabled(type)) {
            return List.of();
        }
        return finish(type, playerId, run.get());
    }

    private List<DetectionResult> finish(CheckType type, String playerId, List<DetectionResult> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<DetectionResult> violations = new ArrayList<>();
        for (DetectionResult result : raw) {
            if (result == null || !result.violation()) {
                continue;
            }
            violations.add(result);
            DetectionEvent event = new DetectionEvent(adapter.serverId(), playerId, playerId, "", type,
                    result.cheatType(), result.confidence(), result.detail(), result.timestampMs());
            safeReport(event);

            if (result.confidence() >= config.confidenceThreshold()) {
                adapter.alert(playerId, buildAlert(result));
                PunishmentType punishment = config.maxPunishment();
                if (punishment != PunishmentType.ALERT) {
                    long duration = punishment == PunishmentType.TEMP_BAN ? TEMP_BAN_DURATION_MS : 0L;
                    adapter.punish(playerId, punishment, result.cheatType(), duration);
                    safeReportPunishment(playerId, punishment, result.cheatType(), duration);
                }
            }
        }
        return violations;
    }

    private List<String> safeMods(String playerId) {
        try {
            List<String> mods = adapter.getInstalledMods(playerId);
            return mods == null ? List.of() : mods;
        } catch (RuntimeException ex) {
            LOG.log(Logger.Level.WARNING, "适配层 getInstalledMods 失败，已忽略", ex);
            return List.of();
        }
    }

    private List<String> safeModHashes(String playerId) {
        try {
            List<String> hashes = adapter.getInstalledModHashes(playerId);
            return hashes == null ? List.of() : hashes;
        } catch (RuntimeException ex) {
            LOG.log(Logger.Level.WARNING, "适配层 getInstalledModHashes 失败，已忽略", ex);
            return List.of();
        }
    }

    private void safeReport(DetectionEvent event) {
        try {
            reporter.report(event);
        } catch (RuntimeException ex) {
            LOG.log(Logger.Level.WARNING, "上报检测事件失败，已忽略", ex);
        }
    }

    private void safeReportPunishment(
            String playerId, PunishmentType type, String reason, long durationMs) {
        try {
            reporter.reportPunishment(adapter.serverId(), playerId, type, reason, durationMs);
        } catch (RuntimeException ex) {
            LOG.log(Logger.Level.WARNING, "上报处置失败，已忽略", ex);
        }
    }

    private static String buildAlert(DetectionResult result) {
        return "PACC 检测到 " + result.cheatType() + "（置信度 " + result.confidence() + "）";
    }

    private List<ServerCheck> checksFor(CheckType type) {
        return checksByType.getOrDefault(type, List.of());
    }
}
