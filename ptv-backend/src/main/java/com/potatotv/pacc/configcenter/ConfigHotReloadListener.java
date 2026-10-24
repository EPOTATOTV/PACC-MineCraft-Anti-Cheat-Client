package com.potatotv.pacc.configcenter;

import com.potatotv.pacc.rule.PrlRuleEngine;
import com.potatotv.pacc.service.HotCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 配置变更后的本地热刷新（R-07）。
 *
 * <p>规则命名空间变更时重载 classpath 规则；运行期阈值变更时清掉远程配置缓存。
 * 只做本地动作——跨实例传播由 {@link ConfigChangeRelay} 的 Redis 广播负责，
 * 否则每个实例收到广播后再次广播会形成风暴。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigHotReloadListener {

    private static final Set<String> CONFIG_CACHE_NAMESPACES =
            Set.of("runtime.config", "detection.threshold", "redscreen.policy");

    private final PrlRuleEngine ruleEngine;
    private final HotCacheService hotCache;

    @EventListener
    public void onConfigChanged(PaccConfigChangedEvent event) {
        String namespace = event.getNamespace() == null ? "" : event.getNamespace().toLowerCase(Locale.ROOT);
        if (namespace.contains("rules")) {
            try {
                int loaded = ruleEngine.reload();
                log.info("配置变更触发规则热加载 namespace={} loaded={}", event.getNamespace(), loaded);
            } catch (Exception e) {
                log.warn("规则热加载失败 namespace={} err={}", event.getNamespace(), e.getMessage());
            }
        }
        if (CONFIG_CACHE_NAMESPACES.contains(namespace)) {
            hotCache.evict(HotCacheService.OPS_ACTIVE_CONFIG_KEY);
        }
    }
}
