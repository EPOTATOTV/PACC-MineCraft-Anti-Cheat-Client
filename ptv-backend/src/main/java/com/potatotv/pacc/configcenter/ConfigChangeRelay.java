package com.potatotv.pacc.configcenter;

import com.potatotv.pacc.service.OnlineStatusService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 配置变更转发器。
 *
 * <p>当配置来源为 Apollo 且引导成功时，订阅配置变更并重新发布为
 * {@link PaccConfigChangedEvent}（进程内业务侧监听），同时向在线玩家端广播一条
 * 不含任何配置值的「配置有更新」通知。非 Apollo 来源时不做任何事。</p>
 *
 * <p>订阅过程全程 try/catch：Apollo 抖动或订阅失败只记录日志，绝不让应用启动失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigChangeRelay {

    private final PaccConfigCenter configCenter;
    private final ConfigCenterProperties props;
    private final ApplicationEventPublisher publisher;
    private final OnlineStatusService onlineStatusService;

    @PostConstruct
    void subscribe() {
        try {
            if (!"apollo".equalsIgnoreCase(configCenter.provider())) {
                log.info("配置中心来源为 {}，不订阅远端变更", configCenter.provider());
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
                    configCenter.addChangeListener(ns, keys -> onChanged(ns, keys));
                } catch (Exception e) {
                    // 单个命名空间订阅失败不阻断其它命名空间
                    log.warn("订阅配置变更失败 namespace={} err={}", ns, e.toString());
                }
            }
            log.info("已订阅 Apollo 配置变更命名空间：{}", namespaces());
        } catch (Exception e) {
            log.warn("初始化配置变更转发失败，忽略并继续启动 err={}", e.toString());
        }
    }

    private void onChanged(String namespace, Set<String> keys) {
        try {
            publisher.publishEvent(new PaccConfigChangedEvent(this, namespace, keys));
            // 通知在线玩家端：配置有更新，客户端可按需重拉。消息不含任何配置值。
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
}
