package com.potatotv.pacc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.cluster.RedisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 检测结果热缓存（R-03）。
 *
 * <p>Redis 可用时只走 Redis：跨实例共享，失效立即生效。Redis 未启用时退化为进程内 TTL 表，
 * 单实例部署仍能挡住重复查询，不必为了缓存强行引入 Redis。</p>
 *
 * <p>缓存值统一按 JSON 存字符串，避免 Java 原生序列化带来的类版本耦合。读到坏数据按未命中处理，
 * 并顺手删键，不让一条脏缓存长期占位。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotCacheService {

    private static final String PREFIX = "pacc:hot:";
    private static final int LOCAL_MAX_ENTRIES = 4096;
    private static final int LOCAL_PRUNE_EVERY = 128;

    private final ObjectProvider<RedisClient> redisProvider;
    private final ObjectMapper mapper;
    private final Map<String, LocalEntry> local = new ConcurrentHashMap<>();
    private final AtomicInteger localOps = new AtomicInteger();

    /** 玩家端检测事件列表的缓存键。 */
    public static String playerDetectionsKey(String pteid) {
        return "player:detections:" + pteid;
    }

    /** 玩家端红屏记录的缓存键。 */
    public static String playerRedsKey(String pteid) {
        return "player:reds:" + pteid;
    }

    /** 运维远程配置（key → 生效值）的缓存键。 */
    public static final String OPS_ACTIVE_CONFIG_KEY = "config:active";

    public <T> T getOrLoad(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        if (key == null || key.isBlank()) {
            return loader.get();
        }
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis != null) {
            return getOrLoadRedis(redis, key, ttl, type, loader);
        }
        return getOrLoadLocal(key, ttl, type, loader);
    }

    public void evict(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        local.remove(key);
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.del(PREFIX + key);
        } catch (RuntimeException e) {
            log.warn("热缓存失效失败 key={} err={}", key, e.getMessage());
        }
    }

    private <T> T getOrLoadRedis(RedisClient redis, String key, Duration ttl,
                                 TypeReference<T> type, Supplier<T> loader) {
        String fullKey = PREFIX + key;
        try {
            String cached = redis.get(fullKey);
            if (cached != null) {
                try {
                    return mapper.readValue(cached, type);
                } catch (Exception e) {
                    redis.del(fullKey);
                }
            }
        } catch (RuntimeException e) {
            // Redis 抖动时直接回源，不把请求拖垮
            log.warn("热缓存读取失败 key={} err={}", key, e.getMessage());
        }
        T value = loader.get();
        if (value != null) {
            try {
                redis.setEx(fullKey, mapper.writeValueAsString(value), Math.max(1L, ttl.toSeconds()));
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("热缓存写入失败 key={} err={}", key, e.getMessage());
            }
        }
        return value;
    }

    private <T> T getOrLoadLocal(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        LocalEntry entry = local.get(key);
        if (entry != null && entry.expiresAt > now) {
            try {
                return mapper.readValue(entry.json, type);
            } catch (Exception e) {
                local.remove(key);
            }
        }
        T value = loader.get();
        if (value != null) {
            try {
                pruneLocal(now);
                local.put(key, new LocalEntry(mapper.writeValueAsString(value), now + ttl.toMillis()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("本地热缓存写入失败 key={} err={}", key, e.getMessage());
            }
        }
        return value;
    }

    private void pruneLocal(long now) {
        boolean periodic = localOps.incrementAndGet() % LOCAL_PRUNE_EVERY == 0;
        if (!periodic && local.size() < LOCAL_MAX_ENTRIES) {
            return;
        }
        local.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
        int overflow = local.size() - LOCAL_MAX_ENTRIES;
        if (overflow <= 0) {
            return;
        }
        Iterator<String> keys = local.keySet().iterator();
        for (int i = 0; i < overflow && keys.hasNext(); i++) {
            keys.next();
            keys.remove();
        }
    }

    private record LocalEntry(String json, long expiresAt) {
    }
}
