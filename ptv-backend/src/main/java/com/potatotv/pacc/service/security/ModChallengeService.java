package com.potatotv.pacc.service.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.cluster.RedisClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mod 清单质询-签名（R-09）。
 *
 * <p>服务端下发一次性随机质询，客户端把质询 ID、nonce 与 Mod 清单摘要一起做 HMAC-SHA256，
 * 随 {@code pacc:mods} v2 报文上报。PTV 侧消费质询并复算签名，确认三件事：
 * 清单没有被替换、质询没有过期、同一个质询没有被重复使用。</p>
 *
 * <p>质询存 Redis（跨实例一次性消费），未启用 Redis 时退化为进程内 Map。签名密钥复用
 * {@code pacc.security.wss-sign-secret}；未配置时验证一律失败，不静默放行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModChallengeService {

    private static final int NONCE_BYTES = 32;
    private static final int MAX_MODS = 128;
    private static final int SHA256_HEX_LENGTH = 64;
    private static final String KEY_PREFIX = "pacc:mod:challenge:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<RedisClient> redisProvider;
    private final ObjectMapper mapper;
    private final Map<String, Pending> localPending = new ConcurrentHashMap<>();

    @Value("${pacc.attestation.mod-challenge-ttl-ms:60000}")
    private long ttlMs;

    @Value("${pacc.attestation.mod-max-elapsed-ms:5000}")
    private long maxElapsedMs;

    @Value("${pacc.security.wss-sign-secret:}")
    private String signSecret;

    public record Challenge(String challengeId, String nonce, Instant issuedAt, Instant expiresAt, long ttlMs) {
    }

    public record ModEntry(String id, String sha256) {
    }

    public record VerifyRequest(String serverId, String pteid, String challengeId, String nonce,
                                List<ModEntry> mods, long elapsedMs, String signature) {
    }

    public record Result(String status, String reason) {
    }

    /** 为指定 PTEID 签发一次性质询。 */
    public Challenge issue(String pteid) {
        String owner = pteid == null ? "" : pteid.trim();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("PTEID 不能为空");
        }
        long ttl = Math.max(1_000L, ttlMs);
        byte[] raw = new byte[NONCE_BYTES];
        RANDOM.nextBytes(raw);
        String nonce = HexFormat.of().formatHex(raw);
        String challengeId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Pending pending = new Pending(owner, nonce, now.plusMillis(ttl));
        store(challengeId, pending, ttl);
        return new Challenge(challengeId, nonce, now, pending.expiresAt(), ttl);
    }

    /** 核验客户端应答；无论成败质询都已消费。 */
    public Result verify(String pteid, VerifyRequest request) {
        if (request == null) {
            return new Result("FAIL", "empty_request");
        }
        String owner = pteid == null ? "" : pteid.trim();
        Pending pending = consume(request.challengeId());
        if (pending == null) {
            return new Result("FAIL", "no_pending_challenge");
        }
        Instant now = Instant.now();
        if (now.isAfter(pending.expiresAt())) {
            return new Result("FAIL", "challenge_expired");
        }
        // 质询本身已绑定 PTEID；游戏服插件不知道玩家 PTEID 时可以省略，由服务端按质询记录判定
        if (!owner.isEmpty() && !pending.pteid().equals(owner)) {
            return new Result("FAIL", "pteid_mismatch");
        }
        if (!pending.nonce().equals(nz(request.nonce()))) {
            return new Result("FAIL", "nonce_mismatch");
        }
        if (request.elapsedMs() < 0L || request.elapsedMs() > Math.max(1L, maxElapsedMs)) {
            return new Result("FAIL", "response_too_slow");
        }
        List<ModEntry> mods = normalizeEntries(request.mods());
        if (mods == null) {
            return new Result("FAIL", "invalid_mod_entry");
        }
        if (signSecret == null || signSecret.isBlank()) {
            log.warn("pacc.security.wss-sign-secret 未配置，Mod 质询签名无法校验");
            return new Result("FAIL", "sign_secret_missing");
        }
        String digest = modsDigest(mods);
        String expected = hmacHex(signSecret,
                canonicalSignaturePayload(request.challengeId(), pending.nonce(), digest, request.elapsedMs()));
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = nz(request.signature()).trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            return new Result("FAIL", "signature_invalid");
        }
        return new Result("PASS", "");
    }

    /** Mod 清单摘要，口径与 pacc-server-core 的 ModHashParser.modsDigest 一致。 */
    public static String modsDigest(List<ModEntry> mods) {
        if (mods == null || mods.isEmpty()) {
            return sha256Hex(new byte[0]);
        }
        List<ModEntry> sorted = new ArrayList<>(mods.size());
        for (ModEntry entry : mods) {
            if (entry == null || entry.sha256() == null) {
                continue;
            }
            sorted.add(new ModEntry(entry.id() == null ? "" : entry.id(), entry.sha256()));
        }
        sorted.sort(Comparator.comparing(ModEntry::id).thenComparing(ModEntry::sha256));
        Set<String> seen = new HashSet<>();
        StringBuilder payload = new StringBuilder(sorted.size() * 80);
        boolean first = true;
        for (ModEntry entry : sorted) {
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

    /** 签名原文：{@code challengeId|nonce|modsDigest|elapsedMs}。 */
    public static String canonicalSignaturePayload(String challengeId, String nonce,
                                                   String modsDigest, long elapsedMs) {
        return nz(challengeId) + "|" + nz(nonce) + "|" + nz(modsDigest) + "|" + elapsedMs;
    }

    private List<ModEntry> normalizeEntries(List<ModEntry> mods) {
        if (mods == null) {
            return List.of();
        }
        if (mods.size() > MAX_MODS) {
            return null;
        }
        List<ModEntry> out = new ArrayList<>(mods.size());
        for (ModEntry entry : mods) {
            if (entry == null) {
                return null;
            }
            String sha = normalizeSha256(entry.sha256());
            if (sha == null) {
                return null;
            }
            out.add(new ModEntry(entry.id() == null ? "" : entry.id().trim(), sha));
        }
        return out;
    }

    private static String normalizeSha256(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() != SHA256_HEX_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return null;
            }
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private void store(String challengeId, Pending pending, long ttl) {
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            localPending.put(challengeId, pending);
            pruneLocal();
            return;
        }
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "pteid", pending.pteid(),
                    "nonce", pending.nonce(),
                    "expires_at", pending.expiresAt().toEpochMilli()));
            redis.setEx(KEY_PREFIX + challengeId, payload, Math.max(1L, ttl / 1000L) + 5L);
        } catch (Exception e) {
            log.warn("Mod 质询写入失败，降级进程内存储: {}", e.getMessage());
            localPending.put(challengeId, pending);
        }
    }

    private Pending consume(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            return null;
        }
        RedisClient redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return localPending.remove(challengeId);
        }
        String payload = null;
        try {
            payload = redis.getdel(KEY_PREFIX + challengeId);
        } catch (RuntimeException e) {
            // 旧版 Redis 不支持 GETDEL 时退回 GET + DEL；极端并发下可能双读，但挑战本身短命
            try {
                payload = redis.get(KEY_PREFIX + challengeId);
                redis.del(KEY_PREFIX + challengeId);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (payload == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(payload);
            return new Pending(node.path("pteid").asText(""),
                    node.path("nonce").asText(""),
                    Instant.ofEpochMilli(node.path("expires_at").asLong(0L)));
        } catch (Exception e) {
            return null;
        }
    }

    private void pruneLocal() {
        Instant now = Instant.now();
        localPending.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
        if (localPending.size() > 10_000) {
            localPending.clear();
        }
    }

    private static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("JVM 缺失 SHA-256 实现", e);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private record Pending(String pteid, String nonce, Instant expiresAt) {
    }
}
