package com.potatotv.pacc.server.core.protocol;

import com.potatotv.pacc.addon.Json;

import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code pacc:mods} 上报协议的服务端解析器——本类即协议规格。
 *
 * <h2>通道</h2>
 * <p>通道名 {@code pacc:mods}（Bukkit plugin channel id），方向为<b>客户端 → 服务端单向</b>。服务端只接收，
 * 不在此通道回发数据。</p>
 *
 * <h2>载荷 v1（兼容）</h2>
 * <pre>{@code
 * {"v":1,"mods":[{"id":"<modid>","sha256":"<64位十六进制小写>"}]}
 * }</pre>
 *
 * <h2>载荷 v2（R-09 质询-签名）</h2>
 * <pre>{@code
 * {"v":2,"mods":[{"id":"<modid>","sha256":"<64位十六进制小写>"}],
 *  "proof":{"challenge_id":"...","nonce":"...","signature":"<64位十六进制>","elapsed_ms":12}}
 * }</pre>
 * <p>签名口径：{@code HMAC-SHA256(secret, challengeId|nonce|modsDigest|elapsedMs)}，
 * {@code modsDigest} 见 {@link #modsDigest(List)}。v2 缺少 proof 一律丢弃；v1 仍接受。</p>
 *
 * <h2>约束</h2>
 * <ul>
 *   <li>载荷字节数 ≤ {@value #MAX_PAYLOAD_BYTES}（64KB）。</li>
 *   <li>{@code mods} 条目数 ≤ {@value #MAX_ENTRIES}。</li>
 *   <li>{@code sha256} 必须是 {@value #SHA256_HEX_LENGTH} 位十六进制（大小写均可，解析后统一归一为小写）。</li>
 *   <li>{@code id} 可为空。</li>
 *   <li>任何畸形 / 超限载荷一律丢弃，只记服务端日志，不抛异常、不影响游戏流程。</li>
 * </ul>
 *
 * <h2>发送端</h2>
 * <p>客户端发送端由 {@code pacc-forge-mod} / {@code pacc-fabric-mod} 实现。</p>
 */
public final class ModHashParser {

    /** 客户端 → 服务端单向上报通道名。 */
    public static final String CHANNEL = "pacc:mods";

    /** 载荷字节数上限：64KB。 */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    /** 单次上报的 Mod 条目数上限。 */
    public static final int MAX_ENTRIES = 128;

    /** SHA-256 十六进制字符串长度。 */
    public static final int SHA256_HEX_LENGTH = 64;

    /** 当前协议版本；v1 仅兼容读取。 */
    public static final int PROTOCOL_VERSION = 2;

    private static final int LEGACY_PROTOCOL_VERSION = 1;
    private static final int SIGNATURE_HEX_LENGTH = 64;
    private static final int MIN_NONCE_LENGTH = 16;
    private static final int MAX_NONCE_LENGTH = 128;
    private static final int MAX_CHALLENGE_ID_LENGTH = 128;
    private static final long MAX_ELAPSED_MS = 60_000L;

    private static final Logger LOG = System.getLogger(ModHashParser.class.getName());

    private ModHashParser() {
    }

    /** 解析通道载荷（字节）。任何畸形 / 超限输入返回 {@link Optional#empty()}。 */
    public static Optional<ModHashReport> parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            return discard("载荷超限 " + payload.length + " 字节");
        }
        return parseText(new String(payload, StandardCharsets.UTF_8));
    }

    /** 解析通道载荷（字符串，UTF-8）。任何畸形 / 超限输入返回 {@link Optional#empty()}。 */
    public static Optional<ModHashReport> parse(String payload) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            return discard("载荷超限");
        }
        return parseText(payload);
    }

    private static Optional<ModHashReport> parseText(String text) {
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException ex) {
            // 畸形 JSON：只记日志，绝不上抛，避免恶意载荷打断服务端流程。
            return discard("JSON 解析失败: " + ex.getMessage());
        }
        if (!(root instanceof Map<?, ?> map)) {
            return discard("顶层不是 JSON 对象");
        }
        int version = protocolVersion(map.get("v"));
        if (version < 0) {
            return discard("协议版本不受支持");
        }
        if (!(map.get("mods") instanceof List<?> entries)) {
            return discard("缺少 mods 数组");
        }
        if (entries.size() > MAX_ENTRIES) {
            return discard("mods 条目超限 " + entries.size());
        }
        List<ModHashReport.ModEntry> mods = new ArrayList<>(entries.size());
        for (Object item : entries) {
            ModHashReport.ModEntry entry = parseEntry(item);
            if (entry == null) {
                return discard("存在非法 mods 条目");
            }
            mods.add(entry);
        }
        ModHashReport.Proof proof = null;
        if (version >= PROTOCOL_VERSION) {
            Object rawProof = map.get("proof");
            if (!(rawProof instanceof Map<?, ?> proofMap)) {
                return discard("v2 缺少 proof");
            }
            proof = parseProof(proofMap);
            if (proof == null) {
                return discard("proof 字段非法");
            }
        }
        return Optional.of(new ModHashReport(mods, proof));
    }

    private static ModHashReport.ModEntry parseEntry(Object item) {
        if (!(item instanceof Map<?, ?> entryMap)) {
            return null;
        }
        if (!(entryMap.get("sha256") instanceof String sha256)) {
            return null;
        }
        String normalized = normalizeSha256(sha256);
        if (normalized == null) {
            return null;
        }
        Object id = entryMap.get("id");
        return new ModHashReport.ModEntry(id instanceof String s ? s : null, normalized);
    }

    private static ModHashReport.Proof parseProof(Map<?, ?> proof) {
        if (!(proof.get("challenge_id") instanceof String challengeId)) {
            return null;
        }
        String cid = challengeId.trim();
        if (cid.isEmpty() || cid.length() > MAX_CHALLENGE_ID_LENGTH) {
            return null;
        }
        if (!(proof.get("nonce") instanceof String nonce)
                || nonce.length() < MIN_NONCE_LENGTH || nonce.length() > MAX_NONCE_LENGTH) {
            return null;
        }
        if (!(proof.get("signature") instanceof String signature)
                || signature.length() != SIGNATURE_HEX_LENGTH) {
            return null;
        }
        String sig = signature.toLowerCase(Locale.ROOT);
        if (!isHex(sig)) {
            return null;
        }
        long elapsedMs = proof.get("elapsed_ms") instanceof Number number
                ? number.longValue()
                : -1L;
        if (elapsedMs < 0L || elapsedMs > MAX_ELAPSED_MS) {
            return null;
        }
        return new ModHashReport.Proof(cid, nonce, sig, elapsedMs);
    }

    /** 归一为小写并校验为 64 位十六进制；非法返回 {@code null}。供各平台配置 loader 复用同一套规则。 */
    public static String normalizeSha256(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() != SHA256_HEX_LENGTH) {
            return null;
        }
        if (!isHex(value)) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * Mod 清单摘要：按 (id, sha256) 排序、去重后，以 {@code id\nsha256} 逐行拼接再取 SHA-256。
     * 客户端与服务端必须共用同一口径，否则签名永远对不上。
     */
    public static String modsDigest(List<ModHashReport.ModEntry> mods) {
        if (mods == null || mods.isEmpty()) {
            return sha256Hex(new byte[0]);
        }
        List<ModHashReport.ModEntry> sorted = new ArrayList<>(mods.size());
        for (ModHashReport.ModEntry entry : mods) {
            if (entry == null || entry.sha256() == null) {
                continue;
            }
            sorted.add(new ModHashReport.ModEntry(
                    entry.id() == null ? "" : entry.id(), entry.sha256()));
        }
        sorted.sort(Comparator.comparing(ModHashReport.ModEntry::id)
                .thenComparing(ModHashReport.ModEntry::sha256));
        Set<String> seen = new HashSet<>();
        StringBuilder payload = new StringBuilder(sorted.size() * 80);
        boolean first = true;
        for (ModHashReport.ModEntry entry : sorted) {
            if (!seen.add(entry.id() + '\u0000' + entry.sha256())) {
                continue;
            }
            if (!first) {
                payload.append('\n');
            }
            payload.append(entry.id()).append('\n').append(entry.sha256());
            first = false;
        }
        return sha256Hex(payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 签名原文：与服务端复算、客户端实现、单测三方共用同一串。 */
    public static String canonicalSignaturePayload(String challengeId, String nonce,
                                                   String modsDigest, long elapsedMs) {
        return nz(challengeId) + "|" + nz(nonce) + "|" + nz(modsDigest) + "|" + elapsedMs;
    }

    public static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺失 SHA-256 实现", e);
        }
    }

    /** 缺少 {@code v} 视为 v1；v1 / v2 之外返回 -1。 */
    private static int protocolVersion(Object version) {
        if (version == null) {
            return LEGACY_PROTOCOL_VERSION;
        }
        int parsed;
        if (version instanceof Number number) {
            parsed = number.intValue();
        } else {
            parsed = toIntOrMinusOne(String.valueOf(version));
        }
        return parsed == LEGACY_PROTOCOL_VERSION || parsed == PROTOCOL_VERSION ? parsed : -1;
    }

    private static int toIntOrMinusOne(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static <T> Optional<T> discard(String reason) {
        LOG.log(Logger.Level.WARNING, "丢弃 pacc:mods 载荷: " + reason);
        return Optional.empty();
    }
}
