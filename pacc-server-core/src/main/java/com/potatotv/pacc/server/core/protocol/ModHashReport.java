package com.potatotv.pacc.server.core.protocol;

import java.util.List;

/**
 * 客户端上报的 Mod 文件哈希集合，是 {@code pacc:mods} 载荷（见 {@link ModHashParser}）的解析结果模型。
 *
 * <p>{@code sha256} 在解析阶段已归一为小写；{@code id} 为 modid，可为空。该类不可变。</p>
 *
 * <p>R-09 起协议 v2 可携带 {@link Proof}：服务端随机数质询的 ID、nonce、客户端耗时与
 * HMAC-SHA256 签名。v1 报文没有 proof，按兼容模式接受但不具备防重放保证。</p>
 */
public record ModHashReport(List<ModEntry> mods, Proof proof) {

    public ModHashReport {
        mods = mods == null ? List.of() : List.copyOf(mods);
    }

    /** 兼容 v1：无质询证明。 */
    public ModHashReport(List<ModEntry> mods) {
        this(mods, null);
    }

    /**
     * 单条 Mod 条目。
     *
     * @param id     modid，可为空
     * @param sha256 Mod 文件的 SHA-256，64 位小写十六进制
     */
    public record ModEntry(String id, String sha256) {
    }

    /**
     * 质询证明。
     *
     * @param challengeId 服务端签发的质询 ID（一次性）
     * @param nonce       服务端随机数
     * @param signature   HMAC-SHA256，覆盖质询 ID、nonce、Mod 清单摘要与耗时
     * @param elapsedMs   客户端自述完成签名耗时（毫秒）
     */
    public record Proof(String challengeId, String nonce, String signature, long elapsedMs) {
    }

    /** 全部条目的 sha256，保持上报顺序。 */
    public List<String> hashes() {
        return mods.stream().map(ModEntry::sha256).toList();
    }
}
