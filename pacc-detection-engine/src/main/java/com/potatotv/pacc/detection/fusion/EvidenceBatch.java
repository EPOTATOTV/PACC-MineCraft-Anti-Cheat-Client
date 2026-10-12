package com.potatotv.pacc.detection.fusion;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一批证据：融合判定的输入单元，通常对应一名玩家在一个检测窗口内产生的全部证据。
 *
 * @param playerId             玩家标识
 * @param evidence             证据列表（可为空）
 * @param hasNetworkAnomaly    窗口内是否出现网络异常（高延迟 / 丢包 / 抖动）
 * @param hasPluginInterference 窗口内是否出现插件干扰（其他插件修改了玩家状态）
 * @param context              环境上下文标记，如 {@code creative=true}、{@code flightAllowed=true}、
 *                             {@code lagSpike=true}，供误报抑制读取
 */
public record EvidenceBatch(
        String playerId,
        List<Evidence> evidence,
        boolean hasNetworkAnomaly,
        boolean hasPluginInterference,
        Map<String, String> context) {

    public EvidenceBatch {
        Objects.requireNonNull(playerId, "playerId");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public static EvidenceBatch empty(String playerId) {
        return new EvidenceBatch(playerId, List.of(), false, false, Map.of());
    }

    /** 读取上下文标记，缺失返回空串。 */
    public String flag(String key) {
        return context.getOrDefault(key, "");
    }

    /** 上下文标记是否为真值（"true"/"1"/"yes"）。 */
    public boolean boolFlag(String key) {
        String v = flag(key);
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }
}
