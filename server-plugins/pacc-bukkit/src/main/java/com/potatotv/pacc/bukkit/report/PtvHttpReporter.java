package com.potatotv.pacc.bukkit.report;

import com.potatotv.pacc.server.core.model.DetectionEvent;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerInfo;
import com.potatotv.pacc.server.core.report.ModProofReport;
import com.potatotv.pacc.server.core.report.PtvServerReporter;

import java.lang.System.Logger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 通过 HTTP 把检测事件上报到 PTV 管控后端。
 *
 * <p>所有网络请求都在 {@link CompletableFuture} 异步线程执行，绝不阻塞服务端主线程；失败只记日志。
 * 手写极简 JSON，避免为上报引入额外依赖。请求体用 HMAC-SHA256 签名，签名放在 {@code X-PACC-Signature} 头。
 */
public final class PtvHttpReporter implements PtvServerReporter {

    private static final Logger LOG = System.getLogger(PtvHttpReporter.class.getName());
    private static final String SIGNATURE_HEADER = "X-PACC-Signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String REGISTER_PATH = "/api/v1/server/register";
    private static final String EVENT_PATH = "/api/v1/server/event";
    private static final String PUNISHMENT_PATH = "/api/v1/server/punishment";
    private static final String MOD_VERIFY_PATH = "/api/v1/server/mod-verify";

    private final String baseUrl;
    private final String secret;
    private final HttpClient client;

    public PtvHttpReporter(String baseUrl, String secret) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.secret = secret == null ? "" : secret;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void register(ServerInfo info) {
        if (info == null) {
            return;
        }
        String body = "{" + field("serverId", info.serverId())
                + "," + field("serverType", String.valueOf(info.serverType()))
                + "," + field("pluginVersion", info.pluginVersion())
                + "," + field("serverVersion", info.serverVersion())
                + "," + field("serverName", info.serverName())
                + ",\"maxPlayers\":" + info.maxPlayers() + "}";
        post(REGISTER_PATH, body);
    }

    @Override
    public void report(DetectionEvent event) {
        if (event == null) {
            return;
        }
        String body = "{" + field("serverId", event.serverId())
                + "," + field("playerUuid", event.playerUuid())
                + "," + field("playerName", event.playerName())
                + "," + field("pteid", event.pteid())
                + "," + field("checkType", String.valueOf(event.checkType()))
                + "," + field("cheatType", event.cheatType())
                + ",\"confidence\":" + event.confidence()
                + "," + field("detail", event.detail())
                + ",\"timestampMs\":" + event.timestampMs() + "}";
        post(EVENT_PATH, body);
    }

    @Override
    public void reportPunishment(
            String serverId, String playerUuid, PunishmentType type, String reason, long durationMs) {
        String body = "{" + field("serverId", serverId)
                + "," + field("playerUuid", playerUuid)
                + "," + field("type", String.valueOf(type))
                + "," + field("reason", reason)
                + ",\"durationMs\":" + durationMs + "}";
        post(PUNISHMENT_PATH, body);
    }

    @Override
    public void reportModProof(ModProofReport report, Consumer<Boolean> onResult) {
        if (report == null) {
            if (onResult != null) {
                onResult.accept(false);
            }
            return;
        }
        StringBuilder mods = new StringBuilder("[");
        List<com.potatotv.pacc.server.core.protocol.ModHashReport.ModEntry> entries = report.mods();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                mods.append(',');
            }
            var entry = entries.get(i);
            mods.append('{').append(field("id", entry.id()))
                    .append(',').append(field("sha256", entry.sha256())).append('}');
        }
        mods.append(']');
        String body = "{" + field("serverId", report.serverId())
                + "," + field("playerUuid", report.playerUuid())
                + "," + field("challengeId", report.challengeId())
                + "," + field("nonce", report.nonce())
                + ",\"mods\":" + mods
                + ",\"elapsedMs\":" + report.elapsedMs()
                + "," + field("signature", report.signature()) + "}";
        postWithResult(MOD_VERIFY_PATH, body, onResult);
    }

    private void post(String path, String json) {
        if (baseUrl.isBlank()) {
            return;
        }
        String endpoint = stripTrailingSlash(baseUrl) + path;
        String signature = sign(json);
        CompletableFuture.runAsync(() -> send(endpoint, json, signature));
    }

    private void postWithResult(String path, String json, Consumer<Boolean> onResult) {
        if (baseUrl.isBlank()) {
            if (onResult != null) {
                onResult.accept(false);
            }
            return;
        }
        String endpoint = stripTrailingSlash(baseUrl) + path;
        String signature = sign(json);
        CompletableFuture.runAsync(() -> {
            boolean pass = sendForResult(endpoint, json, signature);
            if (onResult != null) {
                onResult.accept(pass);
            }
        });
    }

    private boolean sendForResult(String endpoint, String json, String signature) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header(SIGNATURE_HEADER, signature)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                LOG.log(Logger.Level.WARNING, "Mod 质询核验返回非成功状态: " + response.statusCode());
                return false;
            }
            String body = response.body() == null ? "" : response.body().replace(" ", "");
            return body.contains("\"status\":\"PASS\"");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            LOG.log(Logger.Level.WARNING, "Mod 质询核验请求失败: " + ex.getMessage());
            return false;
        }
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

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
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
