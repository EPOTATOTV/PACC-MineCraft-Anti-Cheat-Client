package com.potatotv.pacc.service.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-07 管理端 Webhook：文案分类、JSON 转义与空配置短路。
 */
class AdminAlertWebhookServiceTest {

    @Test
    void securityAndTransparencyHaveDistinctPrefixes() {
        assertTrue(AdminAlertWebhookService.formatMessage(
                AlertCategory.SECURITY, "PACC 高置信违规", "risk=95").startsWith("[安全告警]"));
        assertTrue(AdminAlertWebhookService.formatMessage(
                AlertCategory.TRANSPARENCY, "PACC 中置信事件", "conf=75").startsWith("[透明度告警]"));
    }

    @Test
    void feishuPayloadEscapesQuotesAndNewlines() {
        String payload = AdminAlertWebhookService.feishuPayload("a\"b\\c\nd");
        assertTrue(payload.startsWith("{\"msg_type\":\"text\""));
        assertTrue(payload.contains("a\\\"b\\\\c\\nd"));
    }

    @Test
    void discordPayloadEscapesQuotes() {
        assertEquals("{\"content\":\"x\\\"y\"}", AdminAlertWebhookService.discordPayload("x\"y"));
    }

    @Test
    void blankConfigMeansNotConfigured() {
        AdminAlertWebhookService service = new AdminAlertWebhookService(
                true, " ", "", "", 5000);
        assertFalse(service.configured());
        // 未配置时 dispatch 必须是静默 no-op
        service.dispatch(AlertCategory.SECURITY, "t", "d");
        service.shutdown();
    }

    @Test
    void anyConfiguredChannelEnablesDispatch() {
        AdminAlertWebhookService service = new AdminAlertWebhookService(
                true, "https://example.invalid/hook", "", "", 5000);
        assertTrue(service.configured());
        service.shutdown();
    }

    @Test
    void statusReportsChannelsWithoutLeakingUrls() {
        AdminAlertWebhookService service = new AdminAlertWebhookService(
                true, "https://feishu.invalid/hook", "https://discord.invalid/hook",
                "https://transparency.invalid/hook", 4000);
        AdminAlertWebhookService.ChannelStatus status = service.status();
        assertTrue(status.enabled());
        assertTrue(status.configured());
        assertTrue(status.feishuConfigured());
        assertTrue(status.discordConfigured());
        assertTrue(status.transparencyFeishuConfigured());
        assertEquals(4000, status.timeoutMs());
        service.shutdown();
    }
}
