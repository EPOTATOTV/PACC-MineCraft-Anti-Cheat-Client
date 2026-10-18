package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.domain.alert.AlertNotifyQueue;
import com.potatotv.pacc.repository.AlertNotifyQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 聚合告警通知投递（P-07）。
 *
 * <p>修复前 {@code t_alert_notify_queue} 只写不读：告警聚合完成后通知永远停在 PENDING / HELD。
 * 本调度器每 10 秒做两件事：
 * <ol>
 *   <li>把到期的 HELD（低优先级延迟批量）提升为 PENDING；</li>
 *   <li>按优先级映射告警类别，投递到 Discord / 飞书，并标记 SENT。</li>
 * </ol>
 *
 * <p>投递走 {@link AdminAlertWebhookService} 的 best-effort 异步发送；这里只负责
 * 「有没有发出去」的状态推进，不重试。发送失败的最坏结果是管理员没收到这条聚合通知，
 * 原始告警仍在告警中心可查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertNotifyDispatcher {

    private final AlertNotifyQueueRepository queueRepository;
    private final AdminAlertWebhookService webhookService;

    /** 到期 HELD 转 PENDING，并把 PENDING 队列投递出去。 */
    @Scheduled(fixedDelayString = "${pacc.alerts.dispatch-interval-ms:10000}")
    @Transactional
    public void dispatchDue() {
        Instant now = Instant.now();
        promoteHeld(now);
        List<AlertNotifyQueue> due =
                queueRepository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                        AlertNotifyQueue.ST_PENDING, now);
        for (AlertNotifyQueue item : due) {
            try {
                webhookService.dispatch(categoryOf(item.getPriority()),
                        "PACC 聚合告警 " + item.getPriority(), item.getPayload());
                item.setStatus(AlertNotifyQueue.ST_SENT);
                item.setSentAt(now);
                queueRepository.save(item);
            } catch (RuntimeException ex) {
                log.warn("聚合告警投递失败 id={} err={}", item.getId(), ex.getMessage());
            }
        }
        if (!due.isEmpty()) {
            log.info("聚合告警投递完成 count={}", due.size());
        }
    }

    /** P0/P1 是安全告警，P2/P3 是透明度告警。 */
    public static AlertCategory categoryOf(String priority) {
        return "P0".equalsIgnoreCase(priority) || "P1".equalsIgnoreCase(priority)
                ? AlertCategory.SECURITY
                : AlertCategory.TRANSPARENCY;
    }

    private void promoteHeld(Instant now) {
        for (AlertNotifyQueue held : queueRepository.findByStatusOrderByScheduledAtAsc(
                AlertNotifyQueue.ST_HELD)) {
            if (held.getScheduledAt() != null && !held.getScheduledAt().isAfter(now)) {
                held.setStatus(AlertNotifyQueue.ST_PENDING);
                queueRepository.save(held);
            }
        }
    }
}
