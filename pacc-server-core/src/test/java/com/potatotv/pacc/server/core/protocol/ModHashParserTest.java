package com.potatotv.pacc.server.core.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code pacc:mods} 协议解析器的边界用例。覆盖正常、大小写归一、非法 hash 长度、超条目数、
 * 超长载荷、畸形 JSON、空载荷等约束。
 */
class ModHashParserTest {

    private static final String LOWER = "a".repeat(64);
    private static final String UPPER = "A".repeat(64);

    @Test
    void parsesNormalPayload() {
        Optional<ModHashReport> report = ModHashParser.parse(
                "{\"v\":1,\"mods\":[{\"id\":\"baritone\",\"sha256\":\"" + LOWER + "\"}]}");
        assertTrue(report.isPresent());
        assertEquals(1, report.get().mods().size());
        assertEquals("baritone", report.get().mods().get(0).id());
        assertEquals(LOWER, report.get().mods().get(0).sha256());
        assertEquals(List.of(LOWER), report.get().hashes());
    }

    @Test
    void parsesByteArrayPayload() {
        byte[] payload = ("{\"v\":1,\"mods\":[{\"sha256\":\"" + LOWER + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        Optional<ModHashReport> report = ModHashParser.parse(payload);
        assertTrue(report.isPresent());
        assertEquals(List.of(LOWER), report.get().hashes());
    }

    @Test
    void normalizesHashToLowerCase() {
        Optional<ModHashReport> report = ModHashParser.parse(
                "{\"v\":1,\"mods\":[{\"sha256\":\"" + UPPER + "\"}]}");
        assertTrue(report.isPresent());
        assertEquals(LOWER, report.get().mods().get(0).sha256());
    }

    @Test
    void idMayBeAbsentOrEmpty() {
        Optional<ModHashReport> report = ModHashParser.parse(
                "{\"v\":1,\"mods\":[{\"sha256\":\"" + LOWER + "\"},{\"id\":\"\",\"sha256\":\"" + LOWER + "\"}]}");
        assertTrue(report.isPresent());
        assertEquals(2, report.get().mods().size());
        assertNull(report.get().mods().get(0).id());
        assertEquals("", report.get().mods().get(1).id());
    }

    @Test
    void versionMayBeAbsent() {
        assertTrue(ModHashParser.parse("{\"mods\":[{\"sha256\":\"" + LOWER + "\"}]}").isPresent());
    }

    @Test
    void unsupportedVersionIsDiscarded() {
        assertTrue(ModHashParser.parse("{\"v\":2,\"mods\":[]}").isEmpty());
        assertTrue(ModHashParser.parse("{\"v\":\"x\",\"mods\":[]}").isEmpty());
    }

    @Test
    void nonHexSha256IsDiscarded() {
        String nonHex = "z".repeat(64);
        assertTrue(ModHashParser.parse(
                "{\"v\":1,\"mods\":[{\"sha256\":\"" + nonHex + "\"}]}").isEmpty());
    }

    @Test
    void wrongHashLengthIsDiscarded() {
        assertTrue(ModHashParser.parse("{\"v\":1,\"mods\":[{\"sha256\":\"abc\"}]}").isEmpty());
        assertTrue(ModHashParser.parse(
                "{\"v\":1,\"mods\":[{\"sha256\":\"" + LOWER + "a\"}]}").isEmpty());
    }

    @Test
    void nonStringSha256IsDiscarded() {
        assertTrue(ModHashParser.parse("{\"v\":1,\"mods\":[{\"sha256\":123}]}").isEmpty());
    }

    @Test
    void missingSha256IsDiscarded() {
        assertTrue(ModHashParser.parse("{\"v\":1,\"mods\":[{\"id\":\"x\"}]}").isEmpty());
    }

    @Test
    void entryNotObjectIsDiscarded() {
        assertTrue(ModHashParser.parse("{\"v\":1,\"mods\":[\"x\"]}").isEmpty());
    }

    @Test
    void maxEntriesBoundary() {
        assertTrue(ModHashParser.parse(entries(ModHashParser.MAX_ENTRIES)).isPresent());
        assertTrue(ModHashParser.parse(entries(ModHashParser.MAX_ENTRIES + 1)).isEmpty());
    }

    @Test
    void oversizedPayloadIsDiscarded() {
        String huge = "{\"v\":1,\"mods\":[{\"id\":\"" + "x".repeat(ModHashParser.MAX_PAYLOAD_BYTES)
                + "\",\"sha256\":\"" + LOWER + "\"}]}";
        assertTrue(huge.getBytes(StandardCharsets.UTF_8).length > ModHashParser.MAX_PAYLOAD_BYTES);
        assertTrue(ModHashParser.parse(huge).isEmpty());
    }

    @Test
    void malformedJsonIsDiscarded() {
        assertTrue(ModHashParser.parse("{\"v\":1,\"mods\":[").isEmpty());
        assertTrue(ModHashParser.parse("not json").isEmpty());
    }

    @Test
    void emptyOrNullPayloadIsDiscarded() {
        assertTrue(ModHashParser.parse((String) null).isEmpty());
        assertTrue(ModHashParser.parse("").isEmpty());
        assertTrue(ModHashParser.parse((byte[]) null).isEmpty());
        assertTrue(ModHashParser.parse(new byte[0]).isEmpty());
    }

    @Test
    void wrongTopLevelShapeIsDiscarded() {
        assertTrue(ModHashParser.parse("[]").isEmpty());
        assertTrue(ModHashParser.parse("{\"v\":1}").isEmpty());
        assertTrue(ModHashParser.parse("{\"v\":1,\"mods\":{}}").isEmpty());
    }

    @Test
    void normalizeSha256ValidatesAndLowercases() {
        assertEquals(LOWER, ModHashParser.normalizeSha256(UPPER));
        assertEquals(LOWER, ModHashParser.normalizeSha256(" " + LOWER + " "));
        assertNull(ModHashParser.normalizeSha256(null));
        assertNull(ModHashParser.normalizeSha256("xyz"));
        assertNull(ModHashParser.normalizeSha256("g".repeat(64)));
    }

    private static String entries(int count) {
        StringBuilder sb = new StringBuilder("{\"v\":1,\"mods\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"m").append(i).append("\",\"sha256\":\"").append(LOWER).append("\"}");
        }
        return sb.append("]}").toString();
    }
}
