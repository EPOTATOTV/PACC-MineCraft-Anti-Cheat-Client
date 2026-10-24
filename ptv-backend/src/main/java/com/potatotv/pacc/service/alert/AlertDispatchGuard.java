package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.cluster.RedisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 告警投递去重。Redis 可用时用 SET NX EX 做跨实例互斥；不可用时退化为进程内 TTL 表，
 * 保证单实例不重复发送。
 *
 * <p>Redis 故障时选择降级而不是拒绝投递：告警宁可偶尔重复，也不能因为缓存挂了就静默丢失。
 * 降级期间跨实例仍可能重复，这属于可接受的次优结果。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertDispatchGuard {

    private static final long TTL_SECONDS = 300L;
    private static final int LOCAL_PRUNE_EVERY = 256;

    private final ObjectProvider<RedisClient> redisProvider;
    private final Map<String, Long> localLeases = new ConcurrentHashMap<>();
    private final AtomicInteger localCalls = new AtomicInteger();

    /** 抢到返回 true；已被其它实例或本进程占用返回 false。 */
    public boolean tryAcquire(String queueId) {
        if (queueId == null || queueId.isBlank()) {
            return false;
        }
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                return redis.setNxEx(AlertQueueKeys.dedupe(queueId), "1", TTL_SECONDS);
            } catch (RuntimeException e) {
                log.warn("告警去重 Redis 不可用，降级为进程内去重: {}", e.getMessage());
            }
        }
        pruneLocal();
        return localLeases.putIfAbsent(queueId, System.currentTimeMillis() + TTL_SECONDS * 1000L) == null;
    }

    /** 投递失败时释放租约，让重试能够继续。 */
    public void release(String queueId) {
        if (queueId == null || queueId.isBlank()) {
            return;
        }
        localLeases.remove(queueId);
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.del(AlertQueueKeys.dedupe(queueId));
            } catch (RuntimeException e) {
                log.warn("告警去重租约释放失败 id={} err={}", queueId, e.getMessage());
            }
        }
    }

    private void pruneLocal() {
        if (localCalls.incrementAndGet() % LOCAL_PRUNE_EVERY != 0 && localLeases.size() < 4096) {
            return;
        }
        long now = System.currentTimeMillis();
        localLeases.entrySet().removeIf(entry -> entry.getValue() < now);
    }
}
