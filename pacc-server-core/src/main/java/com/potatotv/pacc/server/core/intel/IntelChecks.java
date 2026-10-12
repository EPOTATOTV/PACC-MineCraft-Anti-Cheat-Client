package com.potatotv.pacc.server.core.intel;

import com.potatotv.pacc.detection.signature.SignatureLibrary;
import com.potatotv.pacc.detection.threatintel.CheatCatalog;
import com.potatotv.pacc.server.core.check.ServerCheck;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Intel 检查装配工厂（技术文档 §4.2 server 包升级）。
 *
 * <p>四个检查共享一个 {@link IntelRuntime}：物理预测、行为统计、战斗强化、数据包检测。
 * 全部走既有 {@code ServerCheck} 契约与 {@code DetectionEngine} 流程——默认仅告警，
 * 处置级别由插件配置决定（硬约束：玩家端模式不封禁，服务端默认仅告警）。
 *
 * <p>{@link #defaults(byte[])} 会额外启用证据链（§8.2）：密钥取自插件的 PTV 上报密钥，
 * 未配置或过短时自动降级为「不启用链式哈希」。
 */
public final class IntelChecks {

    /** 证据链密钥最短长度（与 {@code EvidenceChain} 口径一致）。 */
    public static final int MIN_CHAIN_KEY_BYTES = 16;

    private IntelChecks() {
    }

    /** 默认四类 intel 检查（共享运行时，不启用证据链）。 */
    public static List<ServerCheck> defaults() {
        return defaults(null);
    }

    /**
     * 默认四类 intel 检查；{@code evidenceChainKey} 非空且 ≥16 字节时启用证据链（§8.2）。
     */
    public static List<ServerCheck> defaults(byte[] evidenceChainKey) {
        IntelRuntime runtime = new IntelRuntime();
        if (evidenceChainKey != null && evidenceChainKey.length >= MIN_CHAIN_KEY_BYTES) {
            runtime.enableEvidenceChain(evidenceChainKey);
        }
        return List.of(
                new PredictionIntelCheck(runtime),
                new BehaviorIntelCheck(runtime),
                new CombatIntelCheck(runtime),
                new PacketIntelCheck(runtime));
    }

    /** 由 PTV 上报密钥派生证据链密钥（密钥过短返回 null，表示不启用）。 */
    public static byte[] chainKeyFrom(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        return raw.length >= MIN_CHAIN_KEY_BYTES ? raw : null;
    }

    /** 内置指纹目录（M1 验收：指纹条数与类别覆盖可在启动时观测）。 */
    public static CheatCatalog builtinCatalog() {
        return new CheatCatalog(SignatureLibrary.builtin());
    }
}
