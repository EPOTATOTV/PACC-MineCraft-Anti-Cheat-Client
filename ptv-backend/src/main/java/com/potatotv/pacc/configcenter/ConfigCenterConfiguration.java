package com.potatotv.pacc.configcenter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 配置中心装配。
 *
 * <p>默认提供 {@link LocalPaccConfigCenter}（读本地 {@code application.yml}）；仅当显式
 * {@code pacc.config-center.provider=apollo} 且 classpath 上存在 Apollo 客户端时，才换成
 * {@link ApolloPaccConfigCenter}。两处 bean 都用 {@link ConditionalOnMissingBean} 做兜底，
 * 且嵌套配置类先于本类 {@code @Bean} 方法被解析，因此 provider=apollo 时 Apollo 实现胜出、
 * 本地实现自动让位；provider 不是 apollo 时 Apollo 类根本不参与，本地实现生效。</p>
 *
 * <p>@ConditionalOnClass 采用字符串形式，使得 Apollo 缺席时本类仍可安全加载、不会
 * 因缺类而抛 {@code NoClassDefFoundError}。</p>
 */
@Configuration
@EnableConfigurationProperties(ConfigCenterProperties.class)
public class ConfigCenterConfiguration {

    @Bean
    @ConditionalOnMissingBean(PaccConfigCenter.class)
    public PaccConfigCenter localPaccConfigCenter(Environment environment) {
        return new LocalPaccConfigCenter(environment);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.ctrip.framework.apollo.ConfigService")
    @ConditionalOnProperty(prefix = "pacc.config-center", name = "provider", havingValue = "apollo")
    static class ApolloConfigCenterConfiguration {

        @Bean
        @ConditionalOnMissingBean(PaccConfigCenter.class)
        public PaccConfigCenter apolloPaccConfigCenter(ConfigCenterProperties props) {
            return new ApolloPaccConfigCenter(props);
        }
    }
}
