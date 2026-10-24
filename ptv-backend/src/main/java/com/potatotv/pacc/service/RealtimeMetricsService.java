package com.potatotv.pacc.service;

import com.potatotv.pacc.cluster.RedisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 实时指标聚合（R-06）。
 *
 * <p>检测、红屏、告警三类事件在写入侧顺手 INCR 到 Redis，管理端实时概览直接读计数，
 * 不再为了一个数字扫当天全表。日计数保留两天，分钟计数保留一小时，滚动窗口按分钟键求和。</p>
 *
 * <p>Redis 未启用时不记录任何指标，{@link #snapshot()} 返回 {@code null}，调用方回退到
 * 原有数据库查询。这里不做进程内计数，避免单实例与多实例口径不一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeMetricsService {

    private static final String PREFIX = "pacc:metrics:";
    private static final long DAY_TTL_SECONDS = 172_800L;
    private static final long MINUTE_TTL_SECONDS = 3_600L;
    private static final int WINDOW_MINUTES = 30;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
            .withZone(ZoneOffset.UTC);

    private final ObjectProvider<RedisClient> redisProvider;

    public void recordDetection(int riskScore) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        int score = Math.max(0, Math.min(100, riskScore));
        Instant now = Instant.now();
        try {
            bump(redis, daily("detections", now), DAY_TTL_SECONDS);
            bump(redis, minutely("detections", now), MINUTE_TTL_SECONDS);
            bumpBy(redis, daily("risk-sum", now), score, DAY_TTL_SECONDS);
            bump(redis, daily("risk-count", now), DAY_TTL_SECONDS);
        } catch (RuntimeException e) {
            log.warn("检测指标记录失败: {}", e.getMessage());
        }
    }

    public void recordRedscreen() {
        bumpSimple("redscreens");
    }

    public void recordAlert() {
        bumpSimple("alerts");
    }

    /** 实时概览快照；Redis 未启用时返回 null。 */
    public Snapshot snapshot(int onlineCount) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return null;
        }
        Instant now = Instant.now();
        try {
            long detectionsToday = value(redis, daily("detections", now));
            long redscreensToday = value(redis, daily("redscreens", now));
            long alertsToday = value(redis, daily("alerts", now));
            long riskSum = value(redis, daily("risk-sum", now));
            long riskCount = value(redis, daily("risk-count", now));
            long detections30m = windowSum(redis, "detections", now);
            long redscreens30m = windowSum(redis, "redscreens", now);
            double avgRisk = riskCount == 0L ? 0.0 : (double) riskSum / riskCount;
            double detectionRate = detectionsToday == 0L
                    ? 0.0
                    : (double) redscreensToday * 100.0 / detectionsToday;
            return new Snapshot(onlineCount, detectionsToday, detections30m, redscreensToday,
                    redscreens30m, alertsToday, round(avgRisk), round(detectionRate));
        } catch (RuntimeException e) {
            log.warn("实时指标读取失败，回退数据库: {}", e.getMessage());
            return null;
        }
    }

    private void bumpSimple(String metric) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        Instant now = Instant.now();
        try {
            bump(redis, daily(metric, now), DAY_TTL_SECONDS);
            bump(redis, minutely(metric, now), MINUTE_TTL_SECONDS);
        } catch (RuntimeException e) {
            log.warn("指标记录失败 metric={} err={}", metric, e.getMessage());
        }
    }

    private static void bump(RedisClient redis, String key, long ttlSeconds) {
        // 只在首次创建键时设过期，避免每条事件都多一次 EXPIRE 往返
        if (redis.incr(key) == 1L) {
            redis.expire(key, ttlSeconds);
        }
    }

    private static void bumpBy(RedisClient redis, String key, long delta, long ttlSeconds) {
        if (delta <= 0L) {
            return;
        }
        if (redis.incrBy(key, delta) == delta) {
            redis.expire(key, ttlSeconds);
        }
    }

    private static long value(RedisClient redis, String key) {
        String raw = redis.get(key);
        return parseLong(raw);
    }

    /** 滚动窗口按分钟键批量和，避免维护额外的 ZSET。 */
    private static long windowSum(RedisClient redis, String metric, Instant now) {
        List<String> keys = new ArrayList<>(WINDOW_MINUTES);
        for (int i = 0; i < WINDOW_MINUTES; i++) {
            keys.add(minutely(metric, now.minusSeconds(60L * i)));
        }
        long sum = 0L;
        for (String value : redis.mget(keys.toArray(String[]::new))) {
            sum += parseLong(value);
        }
        return sum;
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String daily(String metric, Instant now) {
        return PREFIX + metric + ":day:" + DAY.format(now);
    }

    private static String minutely(String metric, Instant now) {
        return PREFIX + metric + ":min:" + MINUTE.format(now);
    }

    public record Snapshot(long online, long detectionsToday, long detections30m,
                           long redscreensToday, long redscreens30m, long alertsToday,
                           double avgRisk, double detectionRatePercent) {
    }
}
