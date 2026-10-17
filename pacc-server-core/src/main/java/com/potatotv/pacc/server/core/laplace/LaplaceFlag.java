package com.potatotv.pacc.server.core.laplace;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;

import java.util.Locale;

/**
 * 拉普拉斯精灵消费的检测结论。
 *
 * <p>为什么还要一层 DTO，而不直接把 {@link DetectionResult} 交出去：
 * <ul>
 *   <li>{@link #flagId()} 是稳定标识（检查项 + 作弊类型的归一化组合），平台侧按检测项/档位
 *       聚合检出率时不能依赖人读的 detail 文案；</li>
 *   <li>{@link #tier()} 是样本档位标注（min/default/max/hidden），只用于报告分组，不参与判定——
 *       这是拉普拉斯侧「注入幅度 = 阈值锚点 × 档位系数」的口径要求；</li>
 *   <li>{@link #evidenceRef()} 指向证据链尾部 MAC，平台要求每个 flag 能回到完整事件流。</li>
 * </ul>
 */
public record LaplaceFlag(
        String flagId,
        CheckType checkType,
        String cheatType,
        int confidence,
        String tier,
        String detail,
        long timestampMs,
        String evidenceRef) {

    /** 未标注档位时的默认档位，与拉普拉斯 CheatTier 的 default 对齐。 */
    public static final String DEFAULT_TIER = "default";

    public LaplaceFlag {
        tier = tier == null || tier.isBlank() ? DEFAULT_TIER : tier;
        evidenceRef = evidenceRef == null ? "" : evidenceRef;
        detail = detail == null ? "" : detail;
    }

    /** 由检测结论构造 flag；证据链 MAC 若启用会随 detail 一起带出（由 intel 运行时附加）。 */
    public static LaplaceFlag from(DetectionResult result) {
        return new LaplaceFlag(
                flagId(result.checkType(), result.cheatType()),
                result.checkType(),
                result.cheatType(),
                result.confidence(),
                DEFAULT_TIER,
                result.detail(),
                result.timestampMs(),
                evidenceRefOf(result.detail()));
    }

    /** 从 detail 里摘出 {@code evidenceMac=...} 供平台归档；没有就返回空串。 */
    private static String evidenceRefOf(String detail) {
        if (detail == null) {
            return "";
        }
        int marker = detail.indexOf("evidenceMac=");
        if (marker < 0) {
            return "";
        }
        int start = marker + "evidenceMac=".length();
        int end = detail.indexOf(' ', start);
        return end < 0 ? detail.substring(start) : detail.substring(start, end);
    }

    /** 稳定 flag 标识，形如 {@code packet:no_fall}。 */
    public static String flagId(CheckType checkType, String cheatType) {
        String check = checkType == null ? "unknown" : checkType.name().toLowerCase(Locale.ROOT);
        String cheat = cheatType == null ? "unknown" : cheatType.toLowerCase(Locale.ROOT);
        return check + ":" + cheat;
    }
}
