package com.potatotv.pacc.service.alert;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-07 真实 HTTP 集成测试：本地起 HttpServer，验证飞书 / Discord 请求真的发出去。
 *
 * <p>只打回环地址，不出网；覆盖安全告警双渠道、透明度告警独立飞书地址、非 2xx 不影响调用方。
 */
class AdminAlertWebhookIntegrationTest {

    private HttpServer server;
    private final List<String> feishuBodies = new CopyOnWriteArrayList<>();
    private final List<String> discordBodies = new CopyOnWriteArrayList<>();
    private final List<String> transparencyBodies = new CopyOnWriteArrayList<>();
    private final List<String> failBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feishu", exchange -> record(exchange, feishuBodies, 200));
        server.createContext("/discord", exchange -> record(exchange, discordBodies, 204));
        server.createContext("/transparency", exchange -> record(exchange, transparencyBodies, 200));
        server.createContext("/fail", exchange -> record(exchange, failBodies, 500));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void securityAlertReachesFeishuAndDiscord() throws Exception {
        AdminAlertWebhookService service = service("/feishu", "/discord", "");
        service.dispatch(AlertCategory.SECURITY, "高置信违规", "pteid=PT***01 risk=95");

        assertTrue(awaitSize(feishuBodies, 1), "飞书应收到请求");
        assertTrue(awaitSize(discordBodies, 1), "Discord 应收到请求");
        assertTrue(feishuBodies.get(0).contains("[安全告警]"));
        assertTrue(feishuBodies.get(0).contains("\"msg_type\":\"text\""));
        assertTrue(discordBodies.get(0).contains("[安全告警]"));
        service.shutdown();
    }

    @Test
    void transparencyAlertUsesDedicatedFeishuChannel() throws Exception {
        AdminAlertWebhookService service = service("/feishu", "/discord", "/transparency");
        service.dispatch(AlertCategory.TRANSPARENCY, "中置信事件", "conf=75");

        assertTrue(awaitSize(transparencyBodies, 1), "透明度飞书应收到请求");
        assertTrue(awaitSize(discordBodies, 1), "Discord 应收到请求");
        assertTrue(feishuBodies.isEmpty(), "主飞书地址不应收到透明度告警");
        assertTrue(transparencyBodies.get(0).contains("[透明度告警]"));
        service.shutdown();
    }

    @Test
    void non2xxResponseDoesNotThrow() throws Exception {
        AdminAlertWebhookService service = service("/fail", "", "");
        // 发送是 best-effort：返回 500 也不能冒泡到检测主流程
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.dispatch(AlertCategory.SECURITY, "失败渠道", "detail"));
        assertTrue(awaitSize(failBodies, 1), "失败渠道也应真实收到请求");
        TimeUnit.MILLISECONDS.sleep(200);
        service.shutdown();
    }

    private AdminAlertWebhookService service(String feishu, String discord, String transparency) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AdminAlertWebhookService(true,
                feishu.isEmpty() ? "" : base + feishu,
                discord.isEmpty() ? "" : base + discord,
                transparency.isEmpty() ? "" : base + transparency,
                2000);
    }

    private static void record(com.sun.net.httpserver.HttpExchange exchange,
                               List<String> sink, int status) throws IOException {
        try (exchange) {
            sink.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, -1);
        }
    }

    private static boolean awaitSize(List<String> bodies, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (bodies.size() >= expected) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return bodies.size() >= expected;
    }
}
