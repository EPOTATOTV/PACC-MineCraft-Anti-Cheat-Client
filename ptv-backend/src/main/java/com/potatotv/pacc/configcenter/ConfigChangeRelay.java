package com.potatotv.pacc.configcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.cluster.RedisClient;
import com.potatotv.pacc.service.OnlineStatusService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 配置变更转发器。
 *
 * <p>三个来源汇到同一个出口：Apollo 变更、管理端远程配置更新、其它实例经 Redis 发来的变更。
 * 本地出口统一发布 {@link PaccConfigChangedEvent}（进程内业务侧监听）并给在线玩家端广播
 * 「配置有更新」，消息不含任何配置值。</p>
 *
 * <p>Redis 未启用时退化为单实例转发；Apollo 抖动或订阅失败只记日志，绝不让应用启动失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigChangeRelay {

    /** 配置变更广播频道。 */
    private static final String CHANNEL = "pacc:config:changed";

    private final PaccConfigCenter configCenter;
    private final ConfigCenterProperties props;
    private final ApplicationEventPublisher publisher;
    private final OnlineStatusService onlineStatusService;
    private final ObjectProvider<RedisClient> redisProvider;
    private final ObjectMapper mapper;

    /** 实例标识：Redis 广播会回到本实例，靠它去重，避免同一变更本地处理两次。 */
    private final String instanceId = UUID.randomUUID().toString();

    private volatile RedisClient.Subscription subscription;

    @PostConstruct
    void subscribe() {
        subscribeRedis();
        subscribeApollo();
    }

    /** 管理端远程配置更新后调用：本地生效并广播给其它实例。 */
    public void notifyChanged(String namespace, Set<String> keys) {
        String ns = namespace == null || namespace.isBlank() ? "runtime.config" : namespace;
        Set<String> safeKeys = keys == null ? Set.of() : Set.copyOf(keys);
        applyLocally(ns, safeKeys);
        publishRedis(ns, safeKeys);
    }

    private void subscribeRedis() {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.info("Redis 未启用，配置变更仅在本实例生效");
            return;
        }
        try {
            subscription = redis.subscribe(CHANNEL, this::onRedisMessage);
            log.info("已订阅 Redis 配置变更频道 {}", CHANNEL);
        } catch (Exception e) {
            log.warn("订阅 Redis 配置变更失败，忽略并继续启动 err={}", e.toString());
        }
    }

    private void subscribeApollo() {
        try {
            if (!"apollo".equalsIgnoreCase(configCenter.provider())) {
                log.info("配置中心来源为 {}，不订阅 Apollo 变更", configCenter.provider());
                return;
            }
            if (!props.isBootstrapEnabled()) {
                log.info("Apollo 引导未开启（pacc.config-center.bootstrap-enabled=false），不订阅远端变更");
                return;
            }
            if (!configCenter.available()) {
                log.warn("Apollo 引导未成功，配置保持本地默认值，不订阅远端变更");
                return;
            }
            for (String ns : namespaces()) {
                try {
                    configCenter.addChangeListener(ns, keys -> notifyChanged(ns, keys));
                } catch (Exception e) {
                    // 单个命名空间订阅失败不阻断其它命名空间
                    log.warn("订阅配置变更失败 namespace={} err={}", ns, e.toString());
                }
            }
            log.info("已订阅 Apollo 配置变更命名空间：{}", namespaces());
        } catch (Exception e) {
            log.warn("初始化 Apollo 配置变更转发失败，忽略并继续启动 err={}", e.toString());
        }
    }

    private void onRedisMessage(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            if (instanceId.equals(node.path("origin").asText())) {
                return; // 自己发的，本地已经处理过
            }
            String namespace = node.path("namespace").asText("");
            if (namespace.isBlank()) {
                return;
            }
            Set<String> keys = new LinkedHashSet<>();
            node.path("keys").forEach(item -> {
                String key = item.asText("");
                if (!key.isBlank()) {
                    keys.add(key);
                }
            });
            applyLocally(namespace, Set.copyOf(keys));
        } catch (Exception e) {
            log.warn("处理 Redis 配置变更失败 err={}", e.toString());
        }
    }

    private void publishRedis(String namespace, Set<String> keys) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "origin", instanceId, "namespace", namespace, "keys", keys));
            redis.publish(CHANNEL, payload);
        } catch (Exception e) {
            log.warn("配置变更广播失败 namespace={} err={}", namespace, e.toString());
        }
    }

    private void applyLocally(String namespace, Set<String> keys) {
        try {
            publisher.publishEvent(new PaccConfigChangedEvent(this, namespace, keys));
            long ack = onlineStatusService.broadcast(
                    "{\"type\":\"config_changed\",\"namespace\":\"" + namespace + "\"}");
            log.info("配置变更已转发 namespace={} keys={} onlineAck={}", namespace, keys, ack);
        } catch (Exception e) {
            log.warn("转发配置变更失败 namespace={} err={}", namespace, e.toString());
        }
    }

    private List<String> namespaces() {
        List<String> ns = props.getNamespaces();
        if (ns == null || ns.isEmpty()) {
            return List.of(props.getDefaultNamespace());
        }
        return ns;
    }

    @PreDestroy
    void closeSubscription() {
        RedisClient.Subscription current = subscription;
        if (current != null) {
            current.close();
        }
    }
}
