package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.cluster.RedisClient;
import com.potatotv.pacc.domain.alert.AlertNotifyQueue;
import com.potatotv.pacc.repository.AlertNotifyQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 单条告警队列项的投递。
 *
 * <p>数据库记录是持久化账本，Redis 只负责「尽快通知」。无论是 Redis 消费还是定时扫描，
 * 都走同一个方法，靠 {@link AlertDispatchGuard} 保证多实例下同一条只发一次。</p>
 *
 * <p>失败按指数退避重排；超过上限转 DEAD 并进死信列表，不再自动重试。
 * 死信列表裁剪到最近 1000 条，避免 Redis 内存被无限堆积。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertNotifyDeliveryService {

    private static final int DEAD_LIST_LIMIT = 1000;
    private static final int LAST_ERROR_MAX = 255;

    private final AlertNotifyQueueRepository queueRepository;
    private final AdminAlertWebhookService webhookService;
    private final AlertDispatchGuard dispatchGuard;
    private final ObjectProvider<RedisClient> redisProvider;

    @Value("${pacc.alerts.dispatch-max-attempts:3}")
    private int maxAttempts;

    @Value("${pacc.alerts.retry-backoff-ms:30000}")
    private long retryBackoffMs;

    @Value("${pacc.alerts.retry-backoff-max-ms:300000}")
    private long retryBackoffMaxMs;

    /**
     * 投递一条队列项。
     *
     * @return true 表示已处理（发送成功或确认由其它实例处理）；false 表示当前未处理
     */
    @Transactional
    public boolean deliver(String queueId) {
        if (queueId == null || queueId.isBlank()) {
            return false;
        }
        AlertNotifyQueue item = queueRepository.findById(queueId).orElse(null);
        if (item == null || !AlertNotifyQueue.ST_PENDING.equals(item.getStatus())) {
            return false;
        }
        Instant now = Instant.now();
        if (item.getScheduledAt() != null && item.getScheduledAt().isAfter(now)) {
            return false;
        }
        if (!dispatchGuard.tryAcquire(queueId)) {
            return true;
        }
        try {
            webhookService.dispatch(AlertNotifyDispatcher.categoryOf(item.getPriority()),
                    "PACC 聚合告警 " + item.getPriority(), item.getPayload());
            item.setStatus(AlertNotifyQueue.ST_SENT);
            item.setSentAt(now);
            item.setLastError(null);
            queueRepository.save(item);
            return true;
        } catch (RuntimeException ex) {
            dispatchGuard.release(queueId);
            retryOrDead(item, now, ex);
            return false;
        }
    }

    private void retryOrDead(AlertNotifyQueue item, Instant now, RuntimeException ex) {
        int attempts = item.getAttempts() + 1;
        item.setAttempts(attempts);
        item.setLastError(truncate(ex.getMessage()));
        if (attempts >= Math.max(1, maxAttempts)) {
            item.setStatus(AlertNotifyQueue.ST_DEAD);
            queueRepository.save(item);
            deadLetter(item.getId());
            log.warn("聚合告警投递超过重试上限，转入死信 id={} attempts={}", item.getId(), attempts);
            return;
        }
        long backoff = Math.min(Math.max(1000L, retryBackoffMaxMs),
                Math.max(1000L, retryBackoffMs) * attempts);
        item.setStatus(AlertNotifyQueue.ST_PENDING);
        item.setScheduledAt(now.plusMillis(backoff));
        queueRepository.save(item);
        requeue(item.getId());
        log.warn("聚合告警投递失败，{}ms 后重试 id={} attempts={} err={}",
                backoff, item.getId(), attempts, ex.getMessage());
    }

    private void requeue(String queueId) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return; // 定时扫描会重新拾取
        }
        try {
            redis.lpush(AlertQueueKeys.QUEUE, queueId);
        } catch (RuntimeException e) {
            log.warn("告警重试入队失败 id={} err={}", queueId, e.getMessage());
        }
    }

    private void deadLetter(String queueId) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.lpush(AlertQueueKeys.DEAD, queueId);
            redis.ltrim(AlertQueueKeys.DEAD, 0, DEAD_LIST_LIMIT - 1L);
        } catch (RuntimeException e) {
            log.warn("死信入队失败 id={} err={}", queueId, e.getMessage());
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= LAST_ERROR_MAX ? message : message.substring(0, LAST_ERROR_MAX);
    }
}
