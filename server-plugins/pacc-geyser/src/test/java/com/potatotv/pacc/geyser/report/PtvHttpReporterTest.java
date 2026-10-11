package com.potatotv.pacc.geyser.report;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PtvHttpReporterTest {

    @Test
    void signMatchesKnownHmacVector() {
        // 向量由独立工具（.NET HMACSHA256）计算，避免在测试里重复实现同一段逻辑。
        assertEquals("6b23653f08c72072554e5dfef9b72efe01fcfe724a950689e991e7bd7089eb3e",
                PtvHttpReporter.sign("s3cr3t", "hello"));
    }

    @Test
    void signIsStableForEmptyInputs() {
        assertEquals(PtvHttpReporter.sign("", ""), PtvHttpReporter.sign(null, null));
    }

    @Test
    void escapeHandlesQuotesAndControlCharacters() {
        assertEquals("a\\\"b", PtvHttpReporter.escape("a\"b"));
        assertEquals("a\\\\b", PtvHttpReporter.escape("a\\b"));
        assertEquals("a\\nb\\tc", PtvHttpReporter.escape("a\nb\tc"));
        assertEquals("\\u0001", PtvHttpReporter.escape("\u0001"));
    }

    @Test
    void eventBodyContainsAllFieldsAndEscapesDetail() {
        DetectionEvent event = new DetectionEvent(
                "srv-1", "uuid-1", "Steve", "pteid-1", CheckType.MOVE,
                "SPEED", 90, "detail \"x\"", 1700000000000L);
        String body = PtvHttpReporter.eventBody(event);
        assertTrue(body.contains("\"serverId\":\"srv-1\""));
        assertTrue(body.contains("\"checkType\":\"MOVE\""));
        assertTrue(body.contains("\"confidence\":90"));
        assertTrue(body.contains("\"timestampMs\":1700000000000"));
        assertTrue(body.contains("\"detail\":\"detail \\\"x\\\"\""));
    }
}
