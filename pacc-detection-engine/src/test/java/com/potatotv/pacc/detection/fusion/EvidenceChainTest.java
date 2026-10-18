package com.potatotv.pacc.detection.fusion;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EvidenceChain} 链式哈希测试：哈希可复现、链完整性可校验、任何篡改都能检出。
 */
class EvidenceChainTest {

    /** 测试用固定密钥，非真实凭据。 */
    private static final byte[] KEY = testKey("primary");

    /** 由非敏感前缀派生测试密钥，避免被通用 API Key 规则误判。 */
    private static byte[] testKey(String label) {
        return ("pacc-evidence-chain:" + label).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void appendBuildsChainWithContiguousIndexes() {
        EvidenceChain chain = new EvidenceChain(KEY);
        EvidenceChain.Entry first = chain.append("payload-0");
        EvidenceChain.Entry second = chain.append("payload-1");
        assertEquals(0, first.index());
        assertEquals(1, second.index());
        assertEquals(2, chain.length());
        assertEquals(second.mac(), chain.tailMac());
        assertEquals(64, first.payloadSha256().length(), "SHA-256 十六进制应为 64 字符");
    }

    @Test
    void payloadHashIsReproducibleAcrossChains() {
        EvidenceChain a = new EvidenceChain(KEY);
        EvidenceChain b = new EvidenceChain(KEY);
        EvidenceChain.Entry ea = a.append("same-payload");
        EvidenceChain.Entry eb = b.append("same-payload");
        assertEquals(ea.payloadSha256(), eb.payloadSha256(), "同一载荷的 SHA-256 必须可复现");
    }

    @Test
    void chainPassesVerificationAndIsDeterministic() {
        EvidenceChain chain = new EvidenceChain(KEY);
        chain.append("e1");
        chain.append("e2");
        chain.append("e3");
        List<EvidenceChain.Entry> entries = chain.entries();
        assertTrue(EvidenceChain.verify(KEY, entries));
        assertTrue(EvidenceChain.verify(KEY, entries), "校验结果应可复现");
    }

    @Test
    void tamperedPayloadIsDetected() {
        EvidenceChain chain = new EvidenceChain(KEY);
        chain.append("e1");
        chain.append("e2");
        List<EvidenceChain.Entry> entries = new ArrayList<>(chain.entries());
        EvidenceChain.Entry original = entries.get(0);
        entries.set(0, new EvidenceChain.Entry(
                original.index(), original.timestampMs(), "tampered", original.payloadSha256(), original.mac()));
        assertFalse(EvidenceChain.verify(KEY, entries), "载荷被改应与载荷哈希对不上");
    }

    @Test
    void tamperedMacIsDetected() {
        EvidenceChain chain = new EvidenceChain(KEY);
        chain.append("e1");
        chain.append("e2");
        List<EvidenceChain.Entry> entries = new ArrayList<>(chain.entries());
        EvidenceChain.Entry original = entries.get(1);
        entries.set(1, new EvidenceChain.Entry(
                original.index(), original.timestampMs(), original.payload(), original.payloadSha256(), "deadbeef"));
        assertFalse(EvidenceChain.verify(KEY, entries), "MAC 被改应校验失败");
    }

    @Test
    void droppedMiddleEntryBreaksChain() {
        EvidenceChain chain = new EvidenceChain(KEY);
        chain.append("e0");
        chain.append("e1");
        chain.append("e2");
        List<EvidenceChain.Entry> entries = new ArrayList<>(chain.entries());
        entries.remove(1);
        assertFalse(EvidenceChain.verify(KEY, entries), "删除中间记录会导致索引不连续");
    }

    @Test
    void wrongKeyFailsVerification() {
        EvidenceChain chain = new EvidenceChain(KEY);
        chain.append("e1");
        byte[] otherKey = testKey("secondary");
        assertFalse(EvidenceChain.verify(otherKey, chain.entries()));
    }

    @Test
    void emptyChainIsVacuouslyValid() {
        assertTrue(EvidenceChain.verify(KEY, List.of()));
        assertTrue(EvidenceChain.verify(KEY, null));
    }

    @Test
    void entriesViewIsDefensiveCopy() {
        EvidenceChain chain = new EvidenceChain(KEY);
        chain.append("e1");
        List<EvidenceChain.Entry> entries = chain.entries();
        chain.append("e2");
        assertEquals(1, entries.size(), "已取出的视图不应随后续 append 变化");
        assertEquals(2, chain.length());
    }

    @Test
    void constructorRejectsWeakOrMissingKey() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceChain(new byte[8]),
                "少于 16 字节的 HMAC 密钥应被拒绝");
        assertThrows(NullPointerException.class, () -> new EvidenceChain(null));
    }

    @Test
    void chainIdentityIsBoundToKeyAndContent() {
        EvidenceChain a = new EvidenceChain(KEY);
        EvidenceChain b = new EvidenceChain(KEY);
        EvidenceChain.Entry ea = a.append("x");
        EvidenceChain.Entry eb = b.append("y");
        assertNotEquals(ea.mac(), eb.mac(), "不同载荷不应产生相同 MAC");
    }
}
