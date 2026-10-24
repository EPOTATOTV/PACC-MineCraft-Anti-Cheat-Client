package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.cluster.RedisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把新入队的告警推给 Redis 消费端。
 *
 * <p>必须在数据库事务提交后再推：事务回滚时队列项并不存在，提前推送会让消费者查不到记录。
 * Redis 不可用或推送失败时不补偿，定时扫描仍会按库里的 PENDING 记录投递。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertQueuePublisher {

    private final ObjectProvider<RedisClient> redisProvider;

    public void publishAfterCommit(String queueId) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null || queueId == null || queueId.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push(redis, queueId);
                }
            });
            return;
        }
        push(redis, queueId);
    }

    private void push(RedisClient redis, String queueId) {
        try {
            redis.lpush(AlertQueueKeys.QUEUE, queueId);
        } catch (RuntimeException e) {
            // 定时扫描会兜底，这里只记日志
            log.warn("告警队列推送失败 id={} err={}", queueId, e.getMessage());
        }
    }
}
