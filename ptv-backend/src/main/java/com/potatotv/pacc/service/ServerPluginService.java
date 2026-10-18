package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.domain.ServerPluginEvent;
import com.potatotv.pacc.domain.ServerPluginPunishment;
import com.potatotv.pacc.domain.ServerPluginRegistration;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import com.potatotv.pacc.repository.ServerPluginEventRepository;
import com.potatotv.pacc.repository.ServerPluginPunishmentRepository;
import com.potatotv.pacc.repository.ServerPluginRegistrationRepository;
import com.potatotv.pacc.service.alert.AdminAlertWebhookService;
import com.potatotv.pacc.service.alert.AlertCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端插件通道服务：注册、检测事件、处置留痕、信誉查询、配置下发。
 *
 * <p>边界：本服务只负责记录与聚合。高分事件经 {@link RedscreenService} 联动红屏，
 * 但 PTV 不据此执行跨服封禁，也不对接任何第三方封禁系统。信誉查询只返回账号维度的聚合结果，
 * 绝不回传玩家端原始检测数据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerPluginService {

    /** 允许的服务端软件类型（与 proto ServerType 对齐）。 */
    private static final Set<String> SERVER_TYPES =
            Set.of("PAPER", "SPIGOT", "NUKKIT", "NUKKIT_MOT", "ALLAYMC", "GEYSER");
    /** 允许的处置类型（与 proto PunishmentType 对齐）。 */
    private static final Set<String> PUNISHMENT_TYPES =
            Set.of("ALERT", "KICK", "TEMP_BAN", "BAN");
    private static final int MAX_RECENT_CHEATS = 5;

    private final ServerPluginRegistrationRepository registrationRepository;
    private final ServerPluginEventRepository eventRepository;
    private final ServerPluginPunishmentRepository punishmentRepository;
    private final AccountRepository accountRepository;
    private final RedscreenAlertRepository alertRepository;
    private final RedscreenService redscreenService;
    private final AdminAlertWebhookService adminAlertWebhook;

    @Value("${pacc.server-plugin.config-version:1}")
    private String configVersion;
    @Value("${pacc.server-plugin.punishment-mode:0}")
    private int punishmentMode;
    @Value("${pacc.server-plugin.enabled-checks:MOVE,COMBAT,PACKET,INVENTORY,INTERACTION,CLIENT_BRAND,ADDON,DATA_CONSISTENCY}")
    private String enabledChecksCsv;
    @Value("${pacc.server-plugin.confidence-threshold:60}")
    private int confidenceThreshold;
    @Value("${pacc.detection.suspicious-low:70}")
    private int suspiciousLow;
    @Value("${pacc.detection.redscreen-threshold:85}")
    private int redscreenThreshold;

    /** 注册 / 更新服务器信息（按 serverId upsert）。 */
    @Transactional
    public void register(RegisterRequest req) {
        if (req == null || isBlank(req.serverId())) {
            throw new IllegalArgumentException("serverId 不能为空");
        }
        String type = normalize(req.serverType());
        if (!SERVER_TYPES.contains(type)) {
            throw new IllegalArgumentException("未知服务端类型");
        }
        Instant now = Instant.now();
        ServerPluginRegistration reg = registrationRepository.findById(req.serverId()).orElse(null);
        if (reg == null) {
            reg = ServerPluginRegistration.builder()
                    .serverId(req.serverId())
                    .registeredAt(now)
                    .build();
        }
        reg.setServerType(type);
        reg.setPluginVersion(req.pluginVersion());
        reg.setServerVersion(req.serverVersion());
        reg.setServerName(req.serverName());
        reg.setMaxPlayers(Math.max(0, req.maxPlayers()));
        reg.setLastSeenAt(now);
        registrationRepository.save(reg);
        log.info("服务端插件注册 serverId={} type={} ver={}", req.serverId(), type, req.pluginVersion());
    }

    /**
     * 记录检测事件；高置信且能关联 PTEID 时联动 PTV 红屏。
     *
     * @return 本次是否触发红屏及告警 ID。
     */
    @Transactional
    public EventAck recordEvent(EventRequest req) {
        if (req == null || isBlank(req.serverId())) {
            throw new IllegalArgumentException("serverId 不能为空");
        }
        if (isBlank(req.checkType())) {
            throw new IllegalArgumentException("checkType 不能为空");
        }
        Instant now = Instant.now();
        ServerPluginEvent event = ServerPluginEvent.builder()
                .id("spe_" + UUID.randomUUID())
                .serverId(req.serverId())
                .playerUuid(req.playerUuid())
                .playerName(req.playerName())
                .pteid(req.pteid())
                .checkType(normalize(req.checkType()))
                .cheatType(req.cheatType())
                .confidence(clampConfidence(req.confidence()))
                .detail(req.detail())
                .eventTsMs(req.timestampMs())
                .receivedAt(now)
                .build();

        // 联动红屏：需能关联到玩家端 PTEID，且置信度达到可疑下限（由 RedscreenService 分级决策）
        if (!isBlank(req.pteid())
                && event.getConfidence() >= suspiciousLow
                && !isBlank(req.cheatType())) {
            try {
                RedscreenAlert alert = redscreenService.decideAndHandle(
                        req.pteid(), req.cheatType(), event.getConfidence(),
                        "SERVER", buildEvidence(req));
                if (alert != null) {
                    event.setRedscreenTriggered(true);
                    event.setAlertId(alert.getAlertId());
                    log.warn("服务端检测联动红屏 serverId={} pteid={} type={} conf={} alert={}",
                            req.serverId(), req.pteid(), req.cheatType(), event.getConfidence(), alert.getAlertId());
                }
            } catch (Exception e) {
                log.warn("服务端事件联动红屏失败 serverId={} err={}", req.serverId(), e.getMessage());
            }
        }
        // P-07：中置信事件（可疑但未到红屏阈值）走透明度告警，让管理员知道系统在观察，
        // 但不要求立即处置。高置信事件由 RedscreenService 发安全告警，避免重复推送。
        if (!isBlank(req.pteid()) && !isBlank(req.cheatType())
                && event.getConfidence() >= suspiciousLow
                && event.getConfidence() < redscreenThreshold) {
            try {
                adminAlertWebhook.dispatch(AlertCategory.TRANSPARENCY,
                        "PACC 中置信事件",
                        "server=" + req.serverId() + " pteid=" + req.pteid()
                                + " type=" + req.cheatType() + " conf=" + event.getConfidence());
            } catch (Exception e) {
                log.warn("透明度告警推送失败 serverId={} err={}", req.serverId(), e.getMessage());
            }
        }
        eventRepository.save(event);
        return new EventAck(event.isRedscreenTriggered(), event.getAlertId());
    }

    /** 记录插件在本服实际执行的处置（留痕，不触发跨服动作）。 */
    @Transactional
    public void recordPunishment(PunishmentRequest req) {
        if (req == null || isBlank(req.serverId())) {
            throw new IllegalArgumentException("serverId 不能为空");
        }
        String type = normalize(req.type());
        if (!PUNISHMENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("未知处置类型");
        }
        punishmentRepository.save(ServerPluginPunishment.builder()
                .id("spp_" + UUID.randomUUID())
                .serverId(req.serverId())
                .playerUuid(req.playerUuid())
                .punishmentType(type)
                .reason(req.reason())
                .durationMs(Math.max(0, req.durationMs()))
                .operator(req.operator())
                .occurredTsMs(req.timestampMs())
                .receivedAt(Instant.now())
                .build());
        log.info("服务端处置留痕 serverId={} player={} type={}", req.serverId(), req.playerUuid(), type);
    }

    /** 信誉查询：只回账号维度聚合结果，不含玩家端原始数据。 */
    public ReputationResponse reputation(String pteid, String playerUuid) {
        Account acc = isBlank(pteid) ? null : accountRepository.findById(pteid).orElse(null);
        int score = acc == null ? 100 : Math.max(0, Math.min(100, acc.getReputation()));
        int redscreenCount = acc == null ? 0 : acc.getTotalRedscreen();
        List<String> recentCheats = new ArrayList<>();
        if (!isBlank(pteid)) {
            Set<String> distinct = new LinkedHashSet<>();
            for (RedscreenAlert a : alertRepository.findTop10ByPteidOrderByOccurredAtDesc(pteid)) {
                if (a.getCheatType() != null && distinct.add(a.getCheatType())) {
                    recentCheats.add(a.getCheatType());
                }
                if (recentCheats.size() >= MAX_RECENT_CHEATS) break;
            }
        }
        return new ReputationResponse(playerUuid, pteid, score, redscreenCount, recentCheats, acc != null);
    }

    /** 配置下发：返回当前生效的检测阈值、启用检查项与处置级别。 */
    public ConfigResponse config() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("confidence_threshold", String.valueOf(confidenceThreshold));
        settings.put("suspicious_low", String.valueOf(suspiciousLow));
        settings.put("redscreen_threshold", String.valueOf(redscreenThreshold));
        List<String> checks = Arrays.stream(enabledChecksCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return new ConfigResponse(configVersion, settings, checks, normalizedPunishmentMode(), Instant.now().toEpochMilli());
    }

    private String buildEvidence(EventRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("server=").append(req.serverId());
        if (!isBlank(req.playerName())) sb.append(" player=").append(req.playerName());
        sb.append(" check=").append(normalize(req.checkType()));
        sb.append(" conf=").append(clampConfidence(req.confidence()));
        if (!isBlank(req.detail())) sb.append(" detail=").append(req.detail());
        return sb.toString();
    }

    /** 处置级别收敛到 0/1/2，其余一律按仅告警。 */
    private int normalizedPunishmentMode() {
        return (punishmentMode >= 0 && punishmentMode <= 2) ? punishmentMode : 0;
    }

    private static int clampConfidence(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static String normalize(String v) {
        return v == null ? "" : v.trim().toUpperCase();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ---------- 请求 / 响应 DTO（字段名与服务端插件 HTTP 报文一致，camelCase） ----------

    public record RegisterRequest(
            String serverId, String serverType, String pluginVersion,
            String serverVersion, String serverName, int maxPlayers) {
    }

    public record EventRequest(
            String serverId, String playerUuid, String playerName, String pteid,
            String checkType, String cheatType, int confidence, String detail, long timestampMs) {
    }

    public record PunishmentRequest(
            String serverId, String playerUuid, String type, String reason,
            long durationMs, String operator, long timestampMs) {
    }

    /** 事件受理结果：是否触发红屏及告警 ID。 */
    public record EventAck(boolean redscreenTriggered, String alertId) {
    }

    public record ReputationResponse(
            String playerUuid, String pteid, int reputationScore,
            int redscreenCount, List<String> recentCheats, boolean hasClient) {
    }

    public record ConfigResponse(
            String configVersion, Map<String, String> settings,
            List<String> enabledChecks, int punishmentMode, long issuedAt) {
    }
}
