package com.potatotv.pacc.cluster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 跨实例分布式锁（R-05）。
 *
 * <p>Redis 可用时用 {@code SET NX EX} 抢锁，值放随机 token，释放前先比对 token，
 * 避免锁过期后误删别人的锁。后台虚拟线程按 TTL 的三分之一续期，进程卡顿超过 TTL
 * 时锁会自动释放，不会出现死锁。</p>
 *
 * <p>Redis 未启用时退回进程内 {@link ReentrantLock}，单实例语义不变。跨实例互斥只在
 * 开启 Redis 后成立，这一点在配置注释里写明，避免运维误判。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterLock {

    private static final String PREFIX = "pacc:lock:";
    private static final long RETRY_INTERVAL_MS = 50L;

    private final ObjectProvider<RedisClient> redisProvider;
    /** 进程内回退用信号量而不是 ReentrantLock：同一线程重复抢锁也应失败，才像分布式锁。 */
    private final Map<String, Semaphore> localLocks = new ConcurrentHashMap<>();

    /** 尝试抢锁；抢不到返回 null。waitMs=0 表示不等待。 */
    public Lease acquire(String name, long ttlSeconds, long waitMs) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("锁名不能为空");
        }
        long ttl = Math.max(1L, ttlSeconds);
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return acquireLocal(name, waitMs);
        }
        String key = PREFIX + name;
        String token = UUID.randomUUID().toString().replace("-", "");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMs));
        while (true) {
            try {
                if (redis.setNxEx(key, token, ttl)) {
                    return new Lease(name, key, token, ttl, redis, null);
                }
            } catch (RuntimeException e) {
                log.warn("分布式锁抢锁失败 name={} err={}", name, e.getMessage());
                return acquireLocal(name, waitMs); // Redis 抖动时退化为进程内互斥
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return null;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(RETRY_INTERVAL_MS)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    public Lease tryAcquire(String name, long ttlSeconds) {
        return acquire(name, ttlSeconds, 0L);
    }

    /** 在锁内执行；抢不到锁时抛 IllegalStateException。 */
    public <T> T withLock(String name, long ttlSeconds, long waitMs, Supplier<T> action) {
        Lease lease = acquire(name, ttlSeconds, waitMs);
        if (lease == null) {
            throw new IllegalStateException("锁被占用: " + name);
        }
        try (lease) {
            return action.get();
        }
    }

    private Lease acquireLocal(String name, long waitMs) {
        Semaphore lock = localLocks.computeIfAbsent(name, key -> new Semaphore(1));
        try {
            boolean locked = waitMs <= 0L
                    ? lock.tryAcquire()
                    : lock.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
            return locked ? new Lease(name, null, null, 0L, null, lock) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 锁租约：close 时停止续期并释放；重复 close 幂等。 */
    public final class Lease implements AutoCloseable {

        private final String name;
        private final String key;
        private final String token;
        private final long ttlSeconds;
        private final RedisClient redis;
        private final Semaphore localLock;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Thread watchdog;

        private Lease(String name, String key, String token, long ttlSeconds,
                      RedisClient redis, Semaphore localLock) {
            this.name = name;
            this.key = key;
            this.token = token;
            this.ttlSeconds = ttlSeconds;
            this.redis = redis;
            this.localLock = localLock;
            this.watchdog = redis == null ? null : startWatchdog();
        }

        private Thread startWatchdog() {
            return Thread.ofVirtual().name("pacc-lock-" + name).start(() -> {
                while (!closed.get()) {
                    try {
                        Thread.sleep(Math.max(200L, ttlSeconds * 1000L / 3L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (closed.get()) {
                        return;
                    }
                    try {
                        // 只续自己的锁：token 不匹配说明锁已过期并被别人拿走
                        if (token.equals(redis.get(key))) {
                            redis.expire(key, ttlSeconds);
                        }
                    } catch (RuntimeException e) {
                        log.warn("分布式锁续期失败 name={} err={}", name, e.getMessage());
                    }
                }
            });
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (watchdog != null) {
                watchdog.interrupt();
            }
            if (redis == null) {
                if (localLock != null) {
                    localLock.release();
                }
                return;
            }
            try {
                if (token.equals(redis.get(key))) {
                    redis.del(key);
                }
            } catch (RuntimeException e) {
                log.warn("分布式锁释放失败 name={} err={}", name, e.getMessage());
            }
        }
    }
}
