package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.alert.AdminAlertWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端告警渠道状态（P-07）：只读，告诉面板 Discord / 飞书有没有配置。
 * 不返回 URL 或密钥，避免把通道凭据带进前端。
 */
@RestController
@RequestMapping("/api/admin/alerts")
@RequiredArgsConstructor
public class AlertChannelController {

    private final AdminAlertWebhookService webhookService;

    @GetMapping("/channels")
    @RequirePermission("alerts:read")
    public AdminAlertWebhookService.ChannelStatus channels() {
        return webhookService.status();
    }
}
