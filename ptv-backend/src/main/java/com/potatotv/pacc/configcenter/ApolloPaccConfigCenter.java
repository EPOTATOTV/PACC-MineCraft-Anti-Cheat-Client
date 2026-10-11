package com.potatotv.pacc.configcenter;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigService;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Apollo 配置中心实现。
 *
 * <p>所有读取都包在 try/catch 里：Apollo 未配置、远端不可达或读取超时都会退回调用方的默认值，
 * 保证「Apollo 不是启动前提」。日志只记录键名与异常摘要，绝不打印配置值（可能含敏感项）。</p>
 */
@Slf4j
public class ApolloPaccConfigCenter implements PaccConfigCenter {

    private final ConfigCenterProperties props;

    public ApolloPaccConfigCenter(ConfigCenterProperties props) {
        this.props = props;
        // Apollo 客户端通过系统属性定位应用与配置服务；只有显式配置时才覆盖，
        // 避免把空值写进系统属性导致 ConfigService 找不到 app.id 而报错。
        if (notBlank(props.getAppId())) {
            System.setProperty("app.id", props.getAppId());
        }
        if (notBlank(props.getMeta())) {
            System.setProperty("apollo.meta", props.getMeta());
        }
    }

    @Override
    public String provider() {
        return "apollo";
    }

    @Override
    public boolean available() {
        try {
            Config config = ConfigService.getConfig(defaultNamespace());
            return config != null && config.getPropertyNames() != null;
        } catch (Throwable t) {
            log.debug("Apollo 配置中心不可用，降级为本地默认值：{}", t.toString());
            return false;
        }
    }

    @Override
    public String getString(String key, String def) {
        try {
            return ConfigService.getConfig(defaultNamespace()).getProperty(key, def);
        } catch (Throwable t) {
            return fallback(key, def, t);
        }
    }

    @Override
    public int getInt(String key, int def) {
        try {
            return ConfigService.getConfig(defaultNamespace()).getIntProperty(key, def);
        } catch (Throwable t) {
            return fallback(key, def, t);
        }
    }

    @Override
    public double getDouble(String key, double def) {
        try {
            return ConfigService.getConfig(defaultNamespace()).getDoubleProperty(key, def);
        } catch (Throwable t) {
            return fallback(key, def, t);
        }
    }

    @Override
    public boolean getBoolean(String key, boolean def) {
        try {
            return ConfigService.getConfig(defaultNamespace()).getBooleanProperty(key, def);
        } catch (Throwable t) {
            return fallback(key, def, t);
        }
    }

    @Override
    public Map<String, String> namespace(String namespace) {
        try {
            Config config = ConfigService.getConfig(namespace);
            Set<String> names = config.getPropertyNames();
            Map<String, String> out = new LinkedHashMap<>();
            if (names != null) {
                for (String name : names) {
                    out.put(name, config.getProperty(name, null));
                }
            }
            return out;
        } catch (Throwable t) {
            log.warn("读取 Apollo 命名空间失败，返回空配置 namespace={} err={}", namespace, t.toString());
            return Map.of();
        }
    }

    @Override
    public void addChangeListener(String namespace, Consumer<Set<String>> listener) {
        try {
            ConfigService.getConfig(namespace).addChangeListener(
                    (ConfigChangeEvent event) -> listener.accept(event.changedKeys()));
        } catch (Throwable t) {
            log.warn("注册 Apollo 变更监听失败 namespace={} err={}", namespace, t.toString());
        }
    }

    private String defaultNamespace() {
        return notBlank(props.getDefaultNamespace()) ? props.getDefaultNamespace() : "application";
    }

    private <T> T fallback(String key, T def, Throwable t) {
        log.debug("读取 Apollo 配置失败，回退默认值 key={} err={}", key, t.toString());
        return def;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
