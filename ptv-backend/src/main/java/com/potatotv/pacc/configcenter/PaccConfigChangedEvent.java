package com.potatotv.pacc.configcenter;

import org.springframework.context.ApplicationEvent;

import java.util.Set;

/**
 * 配置变更事件：某个命名空间下的若干配置键发生了变化。
 *
 * <p>业务侧可监听本事件做规则/阈值的热刷新，无需感知配置来源是本地还是 Apollo。</p>
 */
public class PaccConfigChangedEvent extends ApplicationEvent {

    private final String namespace;
    private final Set<String> changedKeys;

    public PaccConfigChangedEvent(Object source, String namespace, Set<String> changedKeys) {
        super(source);
        this.namespace = namespace;
        this.changedKeys = changedKeys == null ? Set.of() : Set.copyOf(changedKeys);
    }

    public String getNamespace() {
        return namespace;
    }

    public Set<String> getChangedKeys() {
        return changedKeys;
    }
}
