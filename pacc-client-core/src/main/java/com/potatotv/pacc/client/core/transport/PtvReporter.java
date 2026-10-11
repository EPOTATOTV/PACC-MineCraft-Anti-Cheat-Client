package com.potatotv.pacc.client.core.transport;

import com.potatotv.pacc.client.core.model.ClientDetectionEvent;

import java.lang.System.Logger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 通过 HTTP 把玩家端检测事件上报到 PTV 管控后端。
 *
 * <p>网络请求在 {@link CompletableFuture} 异步线程执行，不阻塞游戏主线程；失败只记日志，
 * 不影响本地检测与红屏流程。手写极简 JSON，不为上报引入第三方依赖。
 * 请求体用 HMAC-SHA256 签名（覆盖完整请求体，签名放在 {@code X-PACC-Signature} 头），
 * 只盖头等于没盖。
 */
public final class PtvReporter {

    private static final Logger LOG = System.getLogger(PtvReporter.class.getName());

    static final String SIGNATURE_HEADER = "X-PACC-Signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String EVENT_PATH = "/api/v1/client/event";

    private final String baseUrl;
    private final String secret;
    private final HttpClient client;

    public PtvReporter(String baseUrl, String secret) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.secret = secret == null ? "" : secret;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 上报一条检测事件。{@code baseUrl} 为空时静默跳过（未配置上报通道）。 */
    public void report(ClientDetectionEvent event) {
        if (event == null) {
            return;
        }
        post(EVENT_PATH, buildEventBody(event));
    }

    private void post(String path, String json) {
        if (baseUrl.isBlank()) {
            return;
        }
        String endpoint = stripTrailingSlash(baseUrl) + path;
        String signature = sign(secret, json);
        CompletableFuture.runAsync(() -> send(endpoint, json, signature));
    }

    private void send(String endpoint, String json, String signature) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header(SIGNATURE_HEADER, signature)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                LOG.log(Logger.Level.WARNING, "上报返回非成功状态: " + response.statusCode());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            // 上报失败不影响本地检测流程。
            LOG.log(Logger.Level.WARNING, "上报失败: " + ex.getMessage());
        }
    }

    /** 组装事件请求体。字段顺序固定，便于签名复现与测试。 */
    static String buildEventBody(ClientDetectionEvent event) {
        return "{" + field("pteid", event.pteid())
                + "," + field("playerName", event.playerName())
                + "," + field("clientVersion", event.clientVersion())
                + "," + field("checkType", String.valueOf(event.checkType()))
                + "," + field("cheatType", event.cheatType())
                + ",\"confidence\":" + event.confidence()
                + "," + field("detail", event.detail())
                + ",\"timestampMs\":" + event.timestampMs() + "}";
    }

    /** 对完整请求体做 HMAC-SHA256，返回十六进制小写签名。 */
    static String sign(String secret, String body) {
        try {
            String key = secret == null ? "" : secret;
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            LOG.log(Logger.Level.WARNING, "签名计算失败: " + ex.getMessage());
            return "";
        }
    }

    private static String field(String key, String value) {
        return "\"" + key + "\":" + quote(value);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + escape(value) + "\"";
    }

    /** 极简 JSON 字符串转义，覆盖引号、反斜杠与控制字符。 */
    static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
