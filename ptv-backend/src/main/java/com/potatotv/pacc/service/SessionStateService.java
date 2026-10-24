package com.potatotv.pacc.service;

import com.potatotv.pacc.cluster.RedisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨实例会话状态（R-04）。
 *
 * <p>WebSocket 会话本身无法跨实例转移，这里存的是可共享的那部分：PTEID 在线心跳、
 * 最近一次登录设备快照、红屏锁定状态。三者都带 TTL，实例宕机后不会被当成永久在线。</p>
 *
 * <p>Redis 未启用时所有方法退化为 no-op，调用方无需分支判断。在线人数由
 * {@link OnlineStatusService} 在 Redis 不可用时回退到本地会话计数。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStateService {

    /** 在线心跳 ZSET：member=PTEID，score=最后心跳毫秒。 */
    private static final String PRESENCE = "pacc:session:presence";
    /** 心跳过期时间；实例每 60 秒刷新一次，留 2 倍余量。 */
    private static final long PRESENCE_TTL_SECONDS = 120L;
    /** 设备快照保留一天，够管理端查「最近登录设备」。 */
    private static final long DEVICE_TTL_SECONDS = 86_400L;

    private final ObjectProvider<RedisClient> redisProvider;

    public boolean enabled() {
        return redisProvider.getIfAvailable() != null;
    }

    /** 刷新在线心跳。 */
    public void heartbeat(String pteid) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null || pteid == null || pteid.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            redis.execute("ZADD", PRESENCE, String.valueOf(now), pteid);
            redis.execute("ZREMRANGEBYSCORE", PRESENCE, "0",
                    String.valueOf(now - PRESENCE_TTL_SECONDS * 1000L));
            redis.expire(PRESENCE, PRESENCE_TTL_SECONDS * 2);
        } catch (RuntimeException e) {
            log.warn("在线心跳写入失败 pteid={} err={}", pteid, e.getMessage());
        }
    }

    /** 玩家下线；仅移除该 PTEID 的在线标记。 */
    public void offline(String pteid) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null || pteid == null || pteid.isBlank()) {
            return;
        }
        try {
            redis.execute("ZREM", PRESENCE, pteid);
        } catch (RuntimeException e) {
            log.warn("在线状态移除失败 pteid={} err={}", pteid, e.getMessage());
        }
    }

    /** 跨实例在线人数（按 PTEID 去重）。 */
    public long onlineCount() {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return -1L;
        }
        try {
            long now = System.currentTimeMillis();
            redis.execute("ZREMRANGEBYSCORE", PRESENCE, "0",
                    String.valueOf(now - PRESENCE_TTL_SECONDS * 1000L));
            Object count = redis.execute("ZCARD", PRESENCE);
            return count instanceof Long value ? value : 0L;
        } catch (RuntimeException e) {
            log.warn("在线人数读取失败，回退本地计数: {}", e.getMessage());
            return -1L;
        }
    }

    /** 记录最近一次登录设备快照。 */
    public void rememberDevice(String pteid, String deviceId, String fingerprint, String platform) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null || pteid == null || pteid.isBlank()) {
            return;
        }
        String key = deviceKey(pteid);
        try {
            redis.execute("HSET", key,
                    "deviceId", nz(deviceId),
                    "fingerprint", nz(fingerprint),
                    "platform", nz(platform),
                    "lastSeenAt", String.valueOf(System.currentTimeMillis()));
            redis.expire(key, DEVICE_TTL_SECONDS);
        } catch (RuntimeException e) {
            log.warn("设备快照写入失败 pteid={} err={}", pteid, e.getMessage());
        }
    }

    /** 读取设备快照；Redis 未启用或没有记录时返回空 Map。 */
    public Map<String, String> deviceSnapshot(String pteid) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null || pteid == null || pteid.isBlank()) {
            return Map.of();
        }
        try {
            Object raw = redis.execute("HGETALL", deviceKey(pteid));
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i + 1 < list.size(); i += 2) {
                out.put(String.valueOf(list.get(i)), String.valueOf(list.get(i + 1)));
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("设备快照读取失败 pteid={} err={}", pteid, e.getMessage());
            return Map.of();
        }
    }

    /** 红屏锁定：到期自动解除，避免解锁流程失败后玩家被永久锁死。 */
    public void lockRedscreen(String pteid, long ttlSeconds) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null || pteid == null || pteid.isBlank()) {
            return;
        }
        try {
            redis.setEx(redscreenKey(pteid), "1", Math.max(60L, ttlSeconds));
        } catch (RuntimeException e) {
            log.warn("红屏锁定写入失败 pteid={} err={}", pteid, e.getMessage());
        }
    }

    public void unlockRedscreen(String pteid) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null || pteid == null || pteid.isBlank()) {
            return;
        }
        try {
            redis.del(redscreenKey(pteid));
        } catch (RuntimeException e) {
            log.warn("红屏锁定解除失败 pteid={} err={}", pteid, e.getMessage());
        }
    }

    public boolean isRedscreenLocked(String pteid) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null || pteid == null || pteid.isBlank()) {
            return false;
        }
        try {
            return "1".equals(redis.get(redscreenKey(pteid)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String deviceKey(String pteid) {
        return "pacc:session:device:" + pteid;
    }

    private static String redscreenKey(String pteid) {
        return "pacc:session:redscreen:" + pteid;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
