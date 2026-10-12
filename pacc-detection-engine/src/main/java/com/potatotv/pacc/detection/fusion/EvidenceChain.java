package com.potatotv.pacc.detection.fusion;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 证据链：HMAC-SHA256 链式哈希（技术文档 §8.2 防篡改）。
 *
 * <p>每条链记录 = HMAC(key, index | timestamp | payloadHash | previousMac)，
 * 任何一条记录被篡改（包括删除中间记录）都会导致后续校验失败。
 * 与 PACC 现有安全事件哈希链同构。
 */
public final class EvidenceChain {

    /** 链上单条记录。 */
    public record Entry(long index, long timestampMs, String payload, String payloadSha256, String mac) {
    }

    private static final String HMAC_ALGO = "HmacSHA256";

    private final byte[] key;
    private final List<Entry> entries = new ArrayList<>();
    private String lastMac;

    public EvidenceChain(byte[] hmacKey) {
        this.key = Objects.requireNonNull(hmacKey, "hmacKey").clone();
        if (this.key.length < 16) {
            throw new IllegalArgumentException("HMAC 密钥至少 16 字节");
        }
        this.lastMac = "";
    }

    /** 追加一条证据载荷，返回生成的链记录。 */
    public synchronized Entry append(String payload) {
        Objects.requireNonNull(payload, "payload");
        long index = entries.size();
        long timestamp = System.currentTimeMillis();
        String payloadHash = sha256Hex(payload);
        String mac = hmacHex(index + "|" + timestamp + "|" + payloadHash + "|" + lastMac);
        Entry entry = new Entry(index, timestamp, payload, payloadHash, mac);
        entries.add(entry);
        lastMac = mac;
        return entry;
    }

    /** 当前链长度。 */
    public synchronized int length() {
        return entries.size();
    }

    /** 当前链尾 MAC。 */
    public synchronized String tailMac() {
        return lastMac;
    }

    /** 只读视图。 */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * 校验整条链的完整性：逐条重算 HMAC 并核对索引连续、负载哈希一致、链式引用闭合。
     */
    public static boolean verify(byte[] hmacKey, List<Entry> entries) {
        Objects.requireNonNull(hmacKey, "hmacKey");
        if (entries == null || entries.isEmpty()) {
            return true;
        }
        String prevMac = "";
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.index() != i) {
                return false;
            }
            if (!sha256Hex(e.payload()).equals(e.payloadSha256())) {
                return false;
            }
            String expected = hmac(keyed(hmacKey), i + "|" + e.timestampMs() + "|" + e.payloadSha256() + "|" + prevMac);
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    e.mac().getBytes(StandardCharsets.US_ASCII))) {
                return false;
            }
            prevMac = e.mac();
        }
        return true;
    }

    private static byte[] keyed(byte[] key) {
        return key;
    }

    private String hmacHex(String data) {
        return hmac(key, data);
    }

    private static String hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            // HmacSHA256 在所有标准 JDK 上都存在，初始化用固定密钥不会失败
            throw new IllegalStateException("HMAC 计算失败", ex);
        }
    }

    private static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
