package com.potatotv.pacc.service.alert;

/**
 * 管理端告警类别（P-07）。
 *
 * <p>安全告警：高置信违规，需要管理员尽快介入（红屏 / 查端）。
 * 透明度告警：中置信事件与规则命中，用于让管理员知道系统在做什么，不要求立即处置。
 * 两类告警走同一套 Webhook 通道，但允许配置不同的接收地址与文案前缀。
 */
public enum AlertCategory {
    SECURITY("安全告警"),
    TRANSPARENCY("透明度告警");

    private final String label;

    AlertCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
