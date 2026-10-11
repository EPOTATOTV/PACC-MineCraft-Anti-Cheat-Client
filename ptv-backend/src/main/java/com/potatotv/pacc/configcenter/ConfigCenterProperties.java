package com.potatotv.pacc.configcenter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置中心参数（{@code pacc.config-center.*}）。
 *
 * <p>默认 {@code provider=local}，即不改任何配置时后端行为与接入 Apollo 之前完全一致。</p>
 */
@Data
@ConfigurationProperties(prefix = "pacc.config-center")
public class ConfigCenterProperties {

    /** 配置来源：{@code local}（默认，读 application.yml）或 {@code apollo}。 */
    private String provider = "local";

    /** Apollo 应用标识（app.id）。 */
    private String appId;

    /** Apollo 配置服务地址（meta server，形如 http://host:8080）。留空走客户端默认发现逻辑。 */
    private String meta;

    /** 默认命名空间：无命名空间参数的读取都走它。 */
    private String defaultNamespace = "application";

    /** 需要订阅变更的命名空间列表。 */
    private List<String> namespaces = new ArrayList<>();

    /** 是否启用 Apollo 引导与变更订阅；默认关闭，避免误连。 */
    private boolean bootstrapEnabled = false;
}
