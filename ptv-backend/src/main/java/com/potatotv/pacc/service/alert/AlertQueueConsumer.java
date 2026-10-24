package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.cluster.RedisClient;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Redis 告警队列消费者。
 *
 * <p>应用就绪后才启动：避免在 Flyway 建表完成前收到历史消息。Redis 未启用时不启动，
 * 由 {@link AlertNotifyDispatcher} 的定时扫描负责投递。</p>
 *
 * <p>BRPOP 阻塞 5 秒，拿到队列项 ID 后交给 {@link AlertNotifyDeliveryService}。
 * Redis 断连时退避 1 秒重试，不退出消费线程——否则一次网络抖动会让队列停止消费直到重启。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertQueueConsumer {

    private static final long BLOCK_SECONDS = 5L;

    private final ObjectProvider<RedisClient> redisProvider;
    private final AlertNotifyDeliveryService deliveryService;

    private volatile boolean running = true;
    private Thread worker;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (worker != null) {
            return;
        }
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.info("Redis 未启用，聚合告警由数据库定时扫描投递");
            return;
        }
        worker = Thread.ofVirtual().name("pacc-alert-consumer").start(() -> consume(redis));
        log.info("聚合告警 Redis 队列消费已启动");
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void consume(RedisClient redis) {
        while (running) {
            try {
                String queueId = redis.brpop(BLOCK_SECONDS, AlertQueueKeys.QUEUE);
                if (queueId != null) {
                    deliveryService.deliver(queueId);
                }
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("聚合告警队列消费异常，1 秒后重试: {}", e.getMessage());
                sleepQuietly();
            }
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
