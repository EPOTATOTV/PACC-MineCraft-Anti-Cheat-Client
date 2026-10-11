package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.InspectSession;
import com.potatotv.pacc.repository.InspectSessionRepository;
import com.potatotv.pacc.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 管理员远程查端服务：队列管理、查端会话、结论与解除，以及 30 分钟超时自动断开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 存储层/取值表达式的 null 分析误报
public class InspectService {

    /** 查端结论取值（强制）：确认作弊 / 误报 / 待分析。 */
    static final Set<String> ALLOWED_CONCLUSIONS = Set.of("CONFIRMED_CHEAT", "FALSE_POSITIVE", "PENDING");

    private final InspectSessionRepository sessionRepository;
    private final com.potatotv.pacc.repository.AccountRepository accountRepository;
    private final com.potatotv.pacc.repository.RedscreenAlertRepository alertRepository;
    private final com.potatotv.pacc.repository.CheatRecordRepository cheatRecordRepository;
    private final OnlineStatusService onlineStatusService;
    private final InspectSignalBus inspectSignalBus;

    public int enqueue(String pteid, String alertId) {
        InspectSession s = InspectSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .pteid(pteid)
                .alertId(alertId)
                .state("QUEUED")
                .expiresAt(Instant.now().plusSeconds(30 * 60)) // 单次最长 30 分钟
                .build();
        sessionRepository.save(s);
        // 在线玩家即时通知其进入查端界面（简化：广播事件已含锁定信息）
        return (int) sessionRepository.findByStateOrderByStartedAtDesc("QUEUED").size();
    }

    public List<InspectSession> pending() {
        return sessionRepository.findByStateOrderByStartedAtDesc("QUEUED");
    }

    public List<InspectSession> all() {
        return sessionRepository.findAll();
    }

    /** 管理员开始查端。 */
    @Transactional
    public InspectSession start(String sessionId, String operator) {
        InspectSession s = sessionRepository.findById(sessionId).orElseThrow();
        s.setState("ACTIVE");
        s.setOperator(operator);
        s.setStartedAt(Instant.now());
        s.setAuditLog(s.getAuditLog() + timestamp() + " operator=" + operator + " start-inspect\n");
        sessionRepository.save(s);

        // 建立信令总线玩家腿并下发查端请求（玩家离线则留待管理端腿连接占位）
        WebSocketSession player = onlineStatusService.firstSession(s.getPteid()).orElse(null);
        inspectSignalBus.registerPlayer(sessionId, player, s.getPteid());
        if (player != null) {
            inspectSignalBus.sendToPlayer(sessionId, signal("inspect_request", s, null));
            log.info("已下发查端请求 sessionId={} pteid={}", LogSanitizer.clean(sessionId), LogSanitizer.clean(s.getPteid()));
        } else {
            log.info("查端目标玩家离线，等待其上线/管理端连接 sessionId={} pteid={}", LogSanitizer.clean(sessionId), LogSanitizer.clean(s.getPteid()));
        }
        return s;
    }

    /**
     * 提交查端结论并解除限制。结论强制且必须合法，否则拒绝结束/解锁。
     * <p>conclusion=CONFIRMED_CHEAT 确认作弊（记录永久保留）；FALSE_POSITIVE 误报（撤销记录、恢复信誉）；
     * PENDING 待分析（仅结束会话并留痕，红屏保持锁定，不解除限制）。</p>
     */
    @Transactional
    public com.potatotv.pacc.domain.Account conclude(String sessionId, String conclusion, String note) {
        String normalized = normalizeConclusion(conclusion);
        InspectSession s = sessionRepository.findById(sessionId).orElseThrow();
        s.setConclusion(normalized);
        s.setState("DONE");
        s.setAuditLog(s.getAuditLog() + timestamp() + " conclusion=" + normalized
                + " note=" + (note == null ? "" : note) + "\n");
        sessionRepository.save(s);

        // 更新红屏记录状态：仅带明确结论的结束动作可解锁（PENDING 不解锁）
        var account = accountRepository.findById(s.getPteid()).orElseThrow();
        if ("FALSE_POSITIVE".equals(normalized)) {
            alertRepository.findById(s.getAlertId()).ifPresent(a -> {
                a.setState("FALSE_POSITIVE");
                a.setInspectConclusion("FALSE_POSITIVE");
                a.setResolvedAt(Instant.now());
                alertRepository.save(a);
            });
            account.setStatus("normal");
            account.setReputation(Math.min(100, account.getReputation() + 10));
            // 撤销对应的永久作弊记录（保留行仅标记 revoked，统计不再计入）
            if (s.getAlertId() != null) {
                cheatRecordRepository.revokeByAlert(s.getAlertId());
            }
        } else if ("CONFIRMED_CHEAT".equals(normalized)) {
            alertRepository.findById(s.getAlertId()).ifPresent(a -> {
                a.setState("CONFIRMED");
                a.setInspectConclusion("CONFIRMED_CHEAT");
                a.setResolvedAt(Instant.now());
                alertRepository.save(a);
            });
            account.setStatus(account.getTotalRedscreen() >= 2 ? "high_risk" : "normal");
        }
        accountRepository.save(account);
        // 查端结束：通知玩家端解除监管，并释放信令总线腿
        inspectSignalBus.sendToPlayer(sessionId, signal("inspect_bye", s, normalized));
        inspectSignalBus.sendToAdmin(sessionId, signal("inspect_closed", s, normalized));
        return account;
    }

    /**
     * 扫描并结束超过 30 分钟（expiresAt 已过）的进行中查端：标记 TIMEOUT、停止屏幕共享并写审计。
     * <p>超时不会自动解除红屏——被查端玩家的限制仍保持，必须由管理员给出结论才能解锁。</p>
     */
    @Transactional
    public int expireTimedOut() {
        List<InspectSession> stale = sessionRepository.findByStateAndExpiresAtBefore("ACTIVE", Instant.now());
        for (InspectSession s : stale) {
            s.setState("TIMEOUT");
            s.setAuditLog(s.getAuditLog() + timestamp() + " timeout exceeded-30m state=TIMEOUT (redscreen stays locked)\n");
            sessionRepository.save(s);
            inspectSignalBus.sendToPlayer(s.getSessionId(), signal("inspect_bye", s, "timeout"));
            inspectSignalBus.sendToAdmin(s.getSessionId(), signal("inspect_closed", s, "timeout"));
            log.info("查端会话超时自动断开 sessionId={} pteid={}", s.getSessionId(), s.getPteid());
        }
        return stale.size();
    }

    /** 归一化并校验查端结论；缺失或非法一律抛异常（拒绝结束/解锁）。兼容旧前端 confirmed 写法。 */
    static String normalizeConclusion(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("查端结束必须给出结论：CONFIRMED_CHEAT / FALSE_POSITIVE / PENDING");
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if ("CONFIRMED".equals(v)) v = "CONFIRMED_CHEAT";
        if (!ALLOWED_CONCLUSIONS.contains(v)) {
            throw new IllegalArgumentException("非法查端结论：" + raw + "（允许：CONFIRMED_CHEAT / FALSE_POSITIVE / PENDING）");
        }
        return v;
    }

    /** 组装一条下发/转发给玩家或管理端的查端信令（B1 文本取证协议）。 */
    private String signal(String type, InspectSession s, String extra) {
        StringBuilder sb = new StringBuilder("{\"type\":\"").append(type)
                .append("\",\"session_id\":\"").append(s.getSessionId())
                .append("\",\"pteid\":\"").append(s.getPteid()).append('"');
        if (extra != null) sb.append(",\"extra\":\"").append(extra).append('"');
        return sb.append('}').toString();
    }

    private String timestamp() {
        return java.time.OffsetDateTime.now().toString();
    }
}