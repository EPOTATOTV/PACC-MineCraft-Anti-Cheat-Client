package com.potatotv.pacc.cluster;

import com.potatotv.pacc.service.LoginLockout;
import com.potatotv.pacc.service.LoginThrottle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 多实例共享存储装配：默认进程内；当 {@code pacc.cluster.redis-enabled=true} 时切换为
 * Redis 集中式存储（限流窗口、验证码、告警队列、热缓存、会话状态、分布式锁共用同一连接池）。
 */
@Configuration
public class ClusterConfig {

    /**
     * 全应用共用一个 Redis 客户端，避免限流 / OTP / 告警各建一套连接池。
     * 未开启 Redis 时该 bean 不存在，下面的账本自动回退到进程内实现。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "pacc.cluster.redis-enabled", havingValue = "true")
    RedisClient redisClient(@Value("${pacc.cluster.redis-host:127.0.0.1}") String host,
                            @Value("${pacc.cluster.redis-port:6379}") int port,
                            @Value("${pacc.cluster.redis-auth:}") String auth,
                            @Value("${pacc.cluster.redis-timeout-ms:2000}") int timeoutMs,
                            @Value("${pacc.cluster.redis-pool-size:16}") int poolSize) {
        return new RedisClient(host, port, auth, timeoutMs, poolSize);
    }

    @Bean
    @Primary
    AttemptLedger attemptLedger(ObjectProvider<RedisClient> redisProvider) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis != null) {
            return new RedisAttemptLedger(redis, LoginThrottle.WINDOW_MS);
        }
        return new InMemoryAttemptLedger(LoginThrottle.WINDOW_MS);
    }

    /**
     * 长窗锁定计数专用账本（15 分钟）。与短窗限流账本分开：窗口长度不同，
     * 混用会让「5 次/60s」的限流被 15 分钟窗口稀释。多实例下同样走 Redis。
     */
    @Bean
    AttemptLedger lockoutLedger(ObjectProvider<RedisClient> redisProvider) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis != null) {
            return new RedisAttemptLedger(redis, LoginLockout.WINDOW_MS);
        }
        return new InMemoryAttemptLedger(LoginLockout.WINDOW_MS);
    }

    @Bean
    OtpLedger otpLedger(ObjectProvider<RedisClient> redisProvider) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis != null) {
            return new RedisOtpLedger(redis);
        }
        return new InMemoryOtpLedger(10L * 60_000L);
    }
}
