package com.potatotv.pacc.client.core.transport;

import com.potatotv.pacc.client.core.model.ClientCheckType;
import com.potatotv.pacc.client.core.model.ClientDetectionEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PtvReporterTest {

    private static final ClientDetectionEvent EVENT = new ClientDetectionEvent(
            "PTEID0001", "Player\"One", "6.0.0",
            ClientCheckType.MOD_LIST, "CHEAT_CLIENT", 95,
            "line1\nline2\\tail", 1710000000000L);

    @Test
    void buildEventBodyContainsAllFields() {
        String body = PtvReporter.buildEventBody(EVENT);
        assertTrue(body.contains("\"pteid\":\"PTEID0001\""));
        assertTrue(body.contains("\"checkType\":\"MOD_LIST\""));
        assertTrue(body.contains("\"cheatType\":\"CHEAT_CLIENT\""));
        assertTrue(body.contains("\"confidence\":95"));
        assertTrue(body.contains("\"timestampMs\":1710000000000"));
        // 引号、换行、反斜杠都被转义
        assertTrue(body.contains("\"playerName\":\"Player\\\"One\""));
        assertTrue(body.contains("line1\\nline2\\\\tail"));
    }

    @Test
    void escapeHandlesControlCharacters() {
        assertEquals("a\\tb", PtvReporter.escape("a\tb"));
        assertEquals("\\u0001", PtvReporter.escape("\u0001"));
    }

    @Test
    void signatureCoversFullBody() {
        String body = PtvReporter.buildEventBody(EVENT);
        String signature = PtvReporter.sign("topsecret", body);

        // 与独立实现的 HMAC-SHA256（对完整请求体）一致
        assertEquals(expectedHmac("topsecret", body), signature);
        // 覆盖体：改动正文任一处，签名随之改变（只盖头就会相同）
        assertNotEquals(signature, PtvReporter.sign("topsecret", body + " "));
        assertNotEquals(signature, PtvReporter.sign("topsecret",
                body.replace("CHEAT_CLIENT", "OTHER")));
        // 密钥不同，签名不同
        assertNotEquals(signature, PtvReporter.sign("othersecret", body));
        assertEquals(64, signature.length());
    }

    @Test
    void blankSecretProducesNoSignatureInsteadOfThrowing() {
        // 未配置密钥时不做签名（HMAC 不接受空密钥），返回空签名而不是抛异常中断上报。
        assertTrue(PtvReporter.sign(null, "{}").isEmpty());
        assertTrue(PtvReporter.sign("", "{}").isEmpty());
    }

    private static String expectedHmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
