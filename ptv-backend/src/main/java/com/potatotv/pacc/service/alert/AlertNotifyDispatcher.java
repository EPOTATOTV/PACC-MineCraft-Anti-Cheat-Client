package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.cluster.ClusterLock;
import com.potatotv.pacc.domain.alert.AlertNotifyQueue;
import com.potatotv.pacc.repository.AlertNotifyQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 聚合告警通知投递（P-07 / R-02）。
 *
 * <p>职责收敛为数据库侧兜底：把到期的 HELD 提升为 PENDING，再扫描到期的 PENDING 记录。
 * 正常路径由 {@link AlertQueueConsumer} 从 Redis 队列即时消费；本调度器负责 Redis 未启用、
 * 推送丢失或实例重启后的恢复，两条路径共用 {@link AlertNotifyDeliveryService} 的去重与重试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertNotifyDispatcher {

    private final AlertNotifyQueueRepository queueRepository;
    private final AlertNotifyDeliveryService deliveryService;
    private final ClusterLock clusterLock;

    /** 到期 HELD 转 PENDING，并把到期的 PENDING 队列投递出去。 */
    @Scheduled(fixedDelayString = "${pacc.alerts.dispatch-interval-ms:10000}")
    public void dispatchDue() {
        // 多实例只让一个实例扫库；单条投递仍有去重租约兜底
        ClusterLock.Lease lease = clusterLock.tryAcquire("alert-dispatch-sweep", 30L);
        if (lease == null) {
            return;
        }
        try (lease) {
            Instant now = Instant.now();
            promoteHeld(now);
            List<AlertNotifyQueue> due =
                    queueRepository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                            AlertNotifyQueue.ST_PENDING, now);
            int delivered = 0;
            for (AlertNotifyQueue item : due) {
                try {
                    if (deliveryService.deliver(item.getId())) {
                        delivered++;
                    }
                } catch (RuntimeException ex) {
                    // 单条异常不能中断整批投递
                    log.warn("聚合告警投递失败 id={} err={}", item.getId(), ex.getMessage());
                }
            }
            if (!due.isEmpty()) {
                log.info("聚合告警兜底扫描完成 due={} delivered={}", due.size(), delivered);
            }
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
