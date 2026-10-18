package com.potatotv.pacc.forge.mods;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModHashPayloadBuilder} 的行为用例。
 *
 * <p>分三块：清单构造（规范化 / 排序 / 去重 / 上限）、「文件名可伪造、内容哈希不可伪造」，
 * 以及与 {@link ServerModHashReplica} 配合的 {@code pacc:mods} 通道端到端（含服务端口径的拒绝行为）。
 */
class ModHashPayloadBuilderTest {

    /** sha256("abc")，标准测试向量，避免用被测量自身做期望值。 */
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final String LOWER = "a".repeat(64);

    // ---- 清单构造 ----

    @Test
    void emptyListProducesEmptyModsArray() {
        assertEquals("{\"v\":1,\"mods\":[]}", ModHashPayloadBuilder.build(List.of()));
    }

    @Test
    void nullListProducesEmptyModsArray() {
        assertEquals("{\"v\":1,\"mods\":[]}", ModHashPayloadBuilder.build(null));
    }

    @Test
    void singleModProducesCanonicalPayload() {
        String json = ModHashPayloadBuilder.build(
                List.of(new ModHashPayloadBuilder.InstalledMod("alpha", bytes("abc"))));
        assertEquals("{\"v\":1,\"mods\":[{\"id\":\"alpha\",\"sha256\":\"" + ABC_SHA256 + "\"}]}", json);
    }

    @Test
    void sha256HexMatchesKnownVectors() {
        assertEquals(ABC_SHA256, ModHashPayloadBuilder.sha256Hex(bytes("abc")));
        assertEquals(EMPTY_SHA256, ModHashPayloadBuilder.sha256Hex(new byte[0]));
    }

    @Test
    void buildBytesIsUtf8OfBuild() {
        List<ModHashPayloadBuilder.InstalledMod> mods = List.of(
                new ModHashPayloadBuilder.InstalledMod("alpha", bytes("abc")));
        assertEquals(ModHashPayloadBuilder.build(mods),
                new String(ModHashPayloadBuilder.buildBytes(mods), StandardCharsets.UTF_8));
    }

    @Test
    void buildIsDeterministicForSameInput() {
        List<ModHashPayloadBuilder.InstalledMod> mods = List.of(
                new ModHashPayloadBuilder.InstalledMod("alpha", bytes("abc")),
                new ModHashPayloadBuilder.InstalledMod("beta", bytes("xyz")));
        assertEquals(ModHashPayloadBuilder.build(mods), ModHashPayloadBuilder.build(mods));
    }

    @Test
    void outputOrderIndependentOfInputOrder() {
        ModHashPayloadBuilder.InstalledMod alpha = new ModHashPayloadBuilder.InstalledMod("alpha", bytes("abc"));
        ModHashPayloadBuilder.InstalledMod beta = new ModHashPayloadBuilder.InstalledMod("beta", bytes("xyz"));
        assertEquals(ModHashPayloadBuilder.build(List.of(alpha, beta)),
                ModHashPayloadBuilder.build(List.of(beta, alpha)));
    }

    @Test
    void duplicateEntriesCollapsed() {
        ModHashPayloadBuilder.InstalledMod mod = new ModHashPayloadBuilder.InstalledMod("dup", bytes("abc"));
        String json = ModHashPayloadBuilder.build(List.of(mod, mod, mod));
        assertEquals("{\"v\":1,\"mods\":[{\"id\":\"dup\",\"sha256\":\"" + ABC_SHA256 + "\"}]}", json);
    }

    @Test
    void sameIdDifferentContentKeptSeparately() {
        String json = ModHashPayloadBuilder.build(List.of(
                new ModHashPayloadBuilder.InstalledMod("shadow", bytes("one")),
                new ModHashPayloadBuilder.InstalledMod("shadow", bytes("two"))));
        assertEquals(2, countOccurrences(json, "\"sha256\":"));
    }

    @Test
    void nullIdBecomesEmptyString() {
        String json = ModHashPayloadBuilder.build(
                List.of(new ModHashPayloadBuilder.InstalledMod(null, bytes("abc"))));
        assertEquals("{\"v\":1,\"mods\":[{\"id\":\"\",\"sha256\":\"" + ABC_SHA256 + "\"}]}", json);
    }

    @Test
    void nullContentEntriesSkipped() {
        String json = ModHashPayloadBuilder.build(List.of(
                new ModHashPayloadBuilder.InstalledMod("ok", bytes("abc")),
                new ModHashPayloadBuilder.InstalledMod("broken", null)));
        assertEquals(1, countOccurrences(json, "\"sha256\":"));
        assertTrue(json.contains("\"id\":\"ok\""));
    }

    @Test
    void nullElementSkipped() {
        List<ModHashPayloadBuilder.InstalledMod> mods = new ArrayList<>();
        mods.add(new ModHashPayloadBuilder.InstalledMod("ok", bytes("abc")));
        mods.add(null);
        assertEquals(1, countOccurrences(ModHashPayloadBuilder.build(mods), "\"sha256\":"));
    }

    @Test
    void oversizedListTruncatedToMaxEntries() {
        List<ModHashPayloadBuilder.InstalledMod> mods = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String id = String.format("m%03d", i);
            mods.add(new ModHashPayloadBuilder.InstalledMod(id, bytes(id)));
        }
        String json = ModHashPayloadBuilder.build(mods);
        assertEquals(ModHashPayloadBuilder.MAX_ENTRIES, countOccurrences(json, "\"sha256\":"));
        // 排序后按 id 升序保留前 128 条，最小的被留下，最大的被截掉。
        assertTrue(json.contains("\"id\":\"m000\""));
        assertTrue(json.contains("\"id\":\"m127\""));
        assertFalse(json.contains("\"id\":\"m128\""));
    }

    @Test
    void maxEntriesPayloadWithinByteLimit() {
        List<ModHashPayloadBuilder.InstalledMod> mods = new ArrayList<>();
        for (int i = 0; i < ModHashPayloadBuilder.MAX_ENTRIES; i++) {
            mods.add(new ModHashPayloadBuilder.InstalledMod("mod" + i + "-" + "x".repeat(40), bytes("content" + i)));
        }
        assertTrue(ModHashPayloadBuilder.buildBytes(mods).length <= ModHashPayloadBuilder.MAX_PAYLOAD_BYTES);
    }

    @Test
    void quotesInIdEscaped() {
        String json = ModHashPayloadBuilder.build(
                List.of(new ModHashPayloadBuilder.InstalledMod("we\"ird", bytes("abc"))));
        assertTrue(json.contains("\"id\":\"we\\\"ird\""));
    }

    // ---- 文件名大小写 / 路径分隔符规范化 ----

    @Test
    void normalizeIdLowerCasesAndUnifiesSeparators() {
        assertEquals("mods/foo/bar.jar", ModHashPayloadBuilder.normalizeId("Mods\\Foo\\Bar.JAR"));
        assertEquals("a/b", ModHashPayloadBuilder.normalizeId("a\\b"));
    }

    @Test
    void normalizeIdTrimsSurroundingWhitespace() {
        assertEquals("spaced", ModHashPayloadBuilder.normalizeId("  spaced  "));
    }

    @Test
    void normalizeIdNullBecomesEmptyString() {
        assertEquals("", ModHashPayloadBuilder.normalizeId(null));
    }

    @Test
    void pathLikeIdNormalizedInPayload() {
        String json = ModHashPayloadBuilder.build(
                List.of(new ModHashPayloadBuilder.InstalledMod("Mods\\Foo.JAR", bytes("abc"))));
        assertTrue(json.contains("\"id\":\"mods/foo.jar\""));
    }

    @Test
    void idsDifferingOnlyByCaseAndSeparatorDedup() {
        byte[] content = bytes("abc");
        byte[] payload = ModHashPayloadBuilder.buildBytes(List.of(
                new ModHashPayloadBuilder.InstalledMod("Mods\\Foo.JAR", content),
                new ModHashPayloadBuilder.InstalledMod("mods/foo.jar", content)));
        List<ServerModHashReplica.Entry> entries = parseEntries(payload);
        assertEquals(1, entries.size());
        assertEquals("mods/foo.jar", entries.get(0).id());
    }

    // ---- 文件名可伪造，内容哈希不可伪造 ----

    @Test
    void sameContentDifferentFileNamesShareHash() {
        byte[] content = bytes("identical-jar-bytes");
        List<ServerModHashReplica.Entry> entries = parseEntries(ModHashPayloadBuilder.buildBytes(List.of(
                new ModHashPayloadBuilder.InstalledMod("alpha.jar", content),
                new ModHashPayloadBuilder.InstalledMod("totally-different-name.zip", content))));
        assertEquals(2, entries.size());
        assertNotEquals(entries.get(0).id(), entries.get(1).id());
        // 名字不同不改变哈希：检测只看内容。
        assertEquals(entries.get(0).sha256(), entries.get(1).sha256());
        assertEquals(sha256(content), entries.get(0).sha256());
    }

    @Test
    void differentContentYieldsDifferentHash() {
        List<ServerModHashReplica.Entry> entries = parseEntries(ModHashPayloadBuilder.buildBytes(List.of(
                new ModHashPayloadBuilder.InstalledMod("same-name", bytes("one")),
                new ModHashPayloadBuilder.InstalledMod("same-name", bytes("two")))));
        assertEquals(2, entries.size());
        assertEquals(entries.get(0).id(), entries.get(1).id());
        assertNotEquals(entries.get(0).sha256(), entries.get(1).sha256());
    }

    // ---- 与服务端 ModHashParser 口径对齐的端到端 ----

    @Test
    void endToEndPayloadParsedByServerReplica() {
        byte[] alpha = bytes("alpha-content");
        byte[] beta = bytes("beta-content");
        byte[] payload = ModHashPayloadBuilder.buildBytes(List.of(
                new ModHashPayloadBuilder.InstalledMod("alpha", alpha),
                new ModHashPayloadBuilder.InstalledMod("beta", beta)));
        Optional<List<ServerModHashReplica.Entry>> parsed = ServerModHashReplica.parse(payload);
        assertTrue(parsed.isPresent());
        assertEquals(Set.of("alpha=" + sha256(alpha), "beta=" + sha256(beta)), toPairs(parsed.get()));
    }

    @Test
    void endToEndParsedHashesMatchInputInCanonicalOrder() {
        byte[] alpha = bytes("alpha-content");
        byte[] beta = bytes("beta-content");
        // 故意乱序传入，服务端拿到的哈希顺序应当是规范化排序后的结果。
        List<ServerModHashReplica.Entry> entries = parseEntries(ModHashPayloadBuilder.buildBytes(List.of(
                new ModHashPayloadBuilder.InstalledMod("beta", beta),
                new ModHashPayloadBuilder.InstalledMod("alpha", alpha))));
        assertEquals(List.of(sha256(alpha), sha256(beta)), entries.stream()
                .map(ServerModHashReplica.Entry::sha256).toList());
    }

    @Test
    void endToEndEscapedIdSurvivesRoundTrip() {
        String weirdId = "we\"ird";
        byte[] payload = ModHashPayloadBuilder.buildBytes(
                List.of(new ModHashPayloadBuilder.InstalledMod(weirdId, bytes("abc"))));
        assertTrue(new String(payload, StandardCharsets.UTF_8).contains("\\\""));
        List<ServerModHashReplica.Entry> entries = parseEntries(payload);
        assertEquals(1, entries.size());
        assertEquals(weirdId, entries.get(0).id());
        assertEquals(ABC_SHA256, entries.get(0).sha256());
    }

    @Test
    void endToEndMaxEntriesAcceptedAtBoundary() {
        List<ModHashPayloadBuilder.InstalledMod> mods = new ArrayList<>();
        for (int i = 0; i < ModHashPayloadBuilder.MAX_ENTRIES * 2; i++) {
            mods.add(new ModHashPayloadBuilder.InstalledMod(String.format("m%03d", i), bytes("c" + i)));
        }
        byte[] payload = ModHashPayloadBuilder.buildBytes(mods);
        assertEquals(ModHashPayloadBuilder.MAX_ENTRIES, parseEntries(payload).size());
    }

    // ---- 服务端口径的拒绝行为（版本 / 长度 / 条目数 / 哈希形态） ----

    @Test
    void serverReplicaRejectsUnsupportedVersion() {
        assertTrue(ServerModHashReplica.parse("{\"v\":2,\"mods\":[]}").isEmpty());
        assertTrue(ServerModHashReplica.parse("{\"v\":\"x\",\"mods\":[]}").isEmpty());
    }

    @Test
    void serverReplicaAcceptsMissingVersion() {
        assertTrue(ServerModHashReplica.parse("{\"mods\":[{\"sha256\":\"" + LOWER + "\"}]}").isPresent());
    }

    @Test
    void serverReplicaRejectsOversizedPayload() {
        String huge = "{\"v\":1,\"mods\":[{\"id\":\"" + "x".repeat(ServerModHashReplica.MAX_PAYLOAD_BYTES)
                + "\",\"sha256\":\"" + LOWER + "\"}]}";
        assertTrue(huge.getBytes(StandardCharsets.UTF_8).length > ServerModHashReplica.MAX_PAYLOAD_BYTES);
        assertTrue(ServerModHashReplica.parse(huge).isEmpty());
    }

    @Test
    void serverReplicaRejectsTooManyEntries() {
        StringBuilder json = new StringBuilder("{\"v\":1,\"mods\":[");
        for (int i = 0; i <= ServerModHashReplica.MAX_ENTRIES; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"m").append(i).append("\",\"sha256\":\"").append(LOWER).append("\"}");
        }
        assertTrue(ServerModHashReplica.parse(json.append("]}").toString()).isEmpty());
    }

    @Test
    void serverReplicaRejectsWrongHashLength() {
        assertTrue(ServerModHashReplica.parse("{\"v\":1,\"mods\":[{\"sha256\":\"abc\"}]}").isEmpty());
        assertTrue(ServerModHashReplica.parse(
                "{\"v\":1,\"mods\":[{\"sha256\":\"" + LOWER + "a\"}]}").isEmpty());
    }

    @Test
    void serverReplicaRejectsNonHexHash() {
        assertTrue(ServerModHashReplica.parse(
                "{\"v\":1,\"mods\":[{\"sha256\":\"" + "z".repeat(64) + "\"}]}").isEmpty());
    }

    @Test
    void serverReplicaRejectsMalformedJson() {
        assertTrue(ServerModHashReplica.parse("{\"v\":1,\"mods\":[").isEmpty());
        assertTrue(ServerModHashReplica.parse("not json").isEmpty());
    }

    @Test
    void serverReplicaRejectsEmptyPayload() {
        assertTrue(ServerModHashReplica.parse((byte[]) null).isEmpty());
        assertTrue(ServerModHashReplica.parse(new byte[0]).isEmpty());
        assertTrue(ServerModHashReplica.parse((String) null).isEmpty());
        assertTrue(ServerModHashReplica.parse("").isEmpty());
    }

    @Test
    void serverReplicaRejectsWrongTopLevelShape() {
        assertTrue(ServerModHashReplica.parse("[]").isEmpty());
        assertTrue(ServerModHashReplica.parse("{\"v\":1}").isEmpty());
        assertTrue(ServerModHashReplica.parse("{\"v\":1,\"mods\":{}}").isEmpty());
    }

    // ---- 辅助 ----

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static List<ServerModHashReplica.Entry> parseEntries(byte[] payload) {
        Optional<List<ServerModHashReplica.Entry>> parsed = ServerModHashReplica.parse(payload);
        assertTrue(parsed.isPresent(), "模组侧产出的载荷应当能通过服务端口径解析");
        return parsed.get();
    }

    private static Set<String> toPairs(List<ServerModHashReplica.Entry> entries) {
        Set<String> pairs = new HashSet<>();
        for (ServerModHashReplica.Entry entry : entries) {
            pairs.add(entry.id() + '=' + entry.sha256());
        }
        return pairs;
    }

    /** 独立实现的 sha256，与生产代码互为对照。 */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
