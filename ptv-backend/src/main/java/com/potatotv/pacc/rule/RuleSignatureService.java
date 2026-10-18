package com.potatotv.pacc.rule;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PRL 规则签名校验（P-09 规则市场）。
 *
 * <p>规则链路原来只有无密钥 SHA-256 checksum，只能发现传输损坏，不能证明来源。
 * 这里补一层公钥验签：作者用私钥签名，PTV 用注入的公钥验证。
 *
 * <p>签名内容固定为 {@code ruleName + "\n" + version + "\n" + source}，端到端一致，
 * 避免「只签规则名不签正文」这种等于没签的口径。
 *
 * <p>策略：
 * <ul>
 *   <li>{@code pacc.rules.require-signature=false}（默认）：允许无签名草稿；
 *       一旦带了签名就必须验过，不能拿假签名蒙混；</li>
 *   <li>{@code true}：所有提交必须带有效签名。</li>
 * </ul>
 */
@Service
public class RuleSignatureService {

    private final boolean required;
    private final String publicKeyBase64;
    private volatile PublicKey cachedKey;

    public RuleSignatureService(
            @Value("${pacc.rules.require-signature:false}") boolean required,
            @Value("${pacc.rules.signature-public-key:}") String publicKeyBase64) {
        this.required = required;
        this.publicKeyBase64 = publicKeyBase64 == null ? "" : publicKeyBase64.trim();
    }

    /** 是否强制要求签名。 */
    public boolean required() {
        return required;
    }

    /** 是否配置了可用的公钥。 */
    public boolean keyConfigured() {
        return !publicKeyBase64.isEmpty();
    }

    /**
     * 校验签名。
     *
     * @return true 表示签名有效；无签名且非强制时返回 true
     */
    public boolean verify(String ruleName, String version, String source, String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return !required;
        }
        if (!keyConfigured()) {
            // 给了签名却没有公钥：无法验证，按失败处理，避免「有签名」造成虚假安全感
            return false;
        }
        try {
            PublicKey key = publicKey();
            String algorithm = algorithmFor(key);
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(payload(ruleName, version, source).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (Exception ex) {
            return false;
        }
    }

    /** 签名算法名，供存储与展示。规则签名只接受非对称算法。 */
    public String algorithm() {
        try {
            return algorithmFor(publicKey());
        } catch (Exception ex) {
            return "unknown";
        }
    }

    /** 待签名正文；客户端 / 签名工具必须使用完全相同的拼接口径。 */
    public static String payload(String ruleName, String version, String source) {
        return (ruleName == null ? "" : ruleName) + "\n"
                + (version == null ? "" : version) + "\n"
                + (source == null ? "" : source);
    }

    private PublicKey publicKey() throws Exception {
        PublicKey key = cachedKey;
        if (key != null) {
            return key;
        }
        byte[] der = Base64.getDecoder().decode(publicKeyBase64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        for (String algorithm : new String[] {"RSA", "EC", "Ed25519"}) {
            try {
                key = KeyFactory.getInstance(algorithm).generatePublic(spec);
                break;
            } catch (Exception ignored) {
                // 换下一种算法继续尝试
            }
        }
        if (key == null) {
            throw new IllegalStateException("无法识别规则签名公钥算法");
        }
        cachedKey = key;
        return key;
    }

    private static String algorithmFor(PublicKey key) {
        return switch (key.getAlgorithm()) {
            case "EC" -> "SHA256withECDSA";
            case "Ed25519", "EdDSA" -> "Ed25519";
            default -> "SHA256withRSA";
        };
    }
}
