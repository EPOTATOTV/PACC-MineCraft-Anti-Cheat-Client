package com.potatotv.pacc.service.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 管理端告警 Webhook（P-07）：Discord 与飞书机器人两种渠道。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>发送是 best-effort：网络失败只记日志，绝不影响红屏 / 查端 / 事件落库主流程；</li>
 *   <li>虚拟线程异步发送，单个 Webhook 的超时不影响检测线程；</li>
 *   <li>安全告警与透明度告警带不同前缀；透明度告警可配单独的飞书地址，
 *       未配置时复用主地址，避免运维为了一个可选项再搭一个机器人；</li>
 *   <li>不在这里做重试。告警是「尽快到达」，重试会把 30 秒时效拖成分钟级；
 *       失败由日志与 PTV 内部告警列表兜底。</li>
 * </ul>
 */
@Slf4j
@Service
public class AdminAlertWebhookService {

    private final boolean enabled;
    private final String feishuUrl;
    private final String discordUrl;
    private final String transparencyFeishuUrl;
    private final long timeoutMs;
    private final HttpClient httpClient;

    public AdminAlertWebhookService(
            @Value("${pacc.alert-webhook.enabled:true}") boolean enabled,
            @Value("${pacc.alert-webhook.feishu-url:}") String feishuUrl,
            @Value("${pacc.alert-webhook.discord-url:}") String discordUrl,
            @Value("${pacc.alert-webhook.transparency-feishu-url:}") String transparencyFeishuUrl,
            @Value("${pacc.alert-webhook.timeout-ms:5000}") long timeoutMs) {
        this.enabled = enabled;
        this.feishuUrl = trim(feishuUrl);
        this.discordUrl = trim(discordUrl);
        this.transparencyFeishuUrl = trim(transparencyFeishuUrl);
        this.timeoutMs = Math.max(500, timeoutMs);
        // sendAsync 由 HttpClient 自己的线程池执行；这层只做 best-effort 投递，不额外建线程池。
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();
    }

    /** 是否至少配置了一个渠道。 */
    public boolean configured() {
        return enabled && (!feishuUrl.isEmpty() || !discordUrl.isEmpty());
    }

    /** 渠道状态快照，供管理端展示「通知有没有接上」。 */
    public record ChannelStatus(
            boolean enabled,
            boolean configured,
            boolean feishuConfigured,
            boolean discordConfigured,
            boolean transparencyFeishuConfigured,
            long timeoutMs) {
    }

    /** 当前渠道配置状态；不返回任何 URL / 密钥。 */
    public ChannelStatus status() {
        return new ChannelStatus(enabled, configured(), !feishuUrl.isEmpty(),
                !discordUrl.isEmpty(), !transparencyFeishuUrl.isEmpty(), timeoutMs);
    }

    /** 异步发送一条管理端告警；未配置渠道时静默跳过。 */
    public void dispatch(AlertCategory category, String title, String detail) {
        if (!configured() || category == null) {
            return;
        }
        String text = formatMessage(category, title, detail);
        String feishuTarget = category == AlertCategory.TRANSPARENCY && !transparencyFeishuUrl.isEmpty()
                ? transparencyFeishuUrl
                : feishuUrl;
        if (!feishuTarget.isEmpty()) {
            send(feishuTarget, feishuPayload(text));
        }
        if (!discordUrl.isEmpty()) {
            send(discordUrl, discordPayload(text));
        }
    }

    private void send(String url, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            log.warn("管理端 Webhook 发送失败: {}", error.getMessage());
                        } else if (response.statusCode() >= 300) {
                            log.warn("管理端 Webhook 返回 {}", response.statusCode());
                        }
                    });
        } catch (RuntimeException ex) {
            // URL 非法 / 线程池关闭等异常都不该冒泡到检测主流程
            log.warn("管理端 Webhook 无法发送: {}", ex.getMessage());
        }
    }

    /** 统一文案：类别前缀 + 标题 + 明细。 */
    public static String formatMessage(AlertCategory category, String title, String detail) {
        String prefix = category == null ? "" : "[" + category.label() + "] ";
        String head = title == null || title.isBlank() ? "PACC 告警" : title.trim();
        String tail = detail == null ? "" : detail.trim();
        return tail.isEmpty() ? prefix + head : prefix + head + " | " + tail;
    }

    /** 飞书自定义机器人文本消息。 */
    public static String feishuPayload(String text) {
        return "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + escape(text) + "\"}}";
    }

    /** Discord Webhook 文本消息。 */
    public static String discordPayload(String text) {
        return "{\"content\":\"" + escape(text) + "\"}";
    }

    /** JSON 字符串转义；告警明细可能包含引号、反斜杠与控制字符。 */
    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
