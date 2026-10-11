package com.potatotv.pacc.configcenter;

import org.springframework.core.env.Environment;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 本地配置中心：直接读写 Spring {@link Environment}（即 {@code application.yml} + 环境变量）。
 *
 * <p>这是默认实现，也是 Apollo 缺席/不可用时的兜底。所谓「降级」在本类里没有任何特殊路径——
 * 因为默认就一直走它。</p>
 */
public class LocalPaccConfigCenter implements PaccConfigCenter {

    private final Environment environment;

    public LocalPaccConfigCenter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String getString(String key, String def) {
        return environment.getProperty(key, def);
    }

    @Override
    public int getInt(String key, int def) {
        Integer v = environment.getProperty(key, Integer.class);
        return v == null ? def : v;
    }

    @Override
    public double getDouble(String key, double def) {
        Double v = environment.getProperty(key, Double.class);
        return v == null ? def : v;
    }

    @Override
    public boolean getBoolean(String key, boolean def) {
        Boolean v = environment.getProperty(key, Boolean.class);
        return v == null ? def : v;
    }

    @Override
    public Map<String, String> namespace(String namespace) {
        // 本地来源没有「命名空间」概念，返回空表；调用方据默认值行事。
        return Map.of();
    }

    @Override
    public void addChangeListener(String namespace, Consumer<Set<String>> listener) {
        // 本地配置在进程启动后不热更新，天然无变更可推。
    }
}
