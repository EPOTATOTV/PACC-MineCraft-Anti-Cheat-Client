package com.potatotv.pacc.configcenter;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * PACC 配置中心抽象。
 *
 * <p>把「检测阈值 / 规则 / 红屏策略 / 特征库版本 / 服务端插件」这类运行期参数的来源
 * 从本地 {@code application.yml} 解耦出来，可切换为 Apollo 集中下发。任何实现都必须是
 * 「缺席即降级」的：拿不到远端配置时一律回退到调用方传入的默认值，绝不抛异常打断业务流程。</p>
 */
public interface PaccConfigCenter {

    /** 当前配置来源标识：{@code local} 或 {@code apollo}。 */
    String provider();

    /** 配置来源当前是否可用（远端可连且能读到配置）。 */
    boolean available();

    /** 读取字符串；缺失或出错返回 {@code def}。 */
    String getString(String key, String def);

    /** 读取整型；缺失、非法或出错返回 {@code def}。 */
    int getInt(String key, int def);

    /** 读取浮点；缺失、非法或出错返回 {@code def}。 */
    double getDouble(String key, double def);

    /** 读取布尔；缺失、非法或出错返回 {@code def}。 */
    boolean getBoolean(String key, boolean def);

    /** 读取指定命名空间的全部键值；不可用时返回空 Map。 */
    Map<String, String> namespace(String namespace);

    /**
     * 监听某命名空间的变更，回调参数为发生变化的键集合。
     * 注册失败（如远端不可达）应静默降级，不影响调用方。
     */
    void addChangeListener(String namespace, Consumer<Set<String>> listener);
}
