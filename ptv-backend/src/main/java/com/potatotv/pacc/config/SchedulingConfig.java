package com.potatotv.pacc.config;

import com.potatotv.pacc.service.AlertService;
import com.potatotv.pacc.service.InspectService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * v5.0 定时任务：告警规则引擎周期性评估、远程查端 30 分钟超时自动断开。
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    private final AlertService alertService;
    private final InspectService inspectService;

    /** 每分钟评估一次启用中的告警规则。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void evaluateAlerts() {
        try {
            alertService.evaluateRules();
        } catch (Exception e) {
            // 单轮评估失败不应中断调度
            log.warn("告警规则评估失败 err={}", e.getMessage());
        }
    }

    /** 每分钟扫描一次超时（>30 分钟）的进行中查端并自动断开；红屏不自动解除。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void expireInspectSessions() {
        try {
            int expired = inspectService.expireTimedOut();
            if (expired > 0) log.info("本轮超时断开查端会话数={}", expired);
        } catch (Exception e) {
            // 单轮清理失败不应中断调度
            log.warn("查端超时清理失败 err={}", e.getMessage());
        }
    }
}