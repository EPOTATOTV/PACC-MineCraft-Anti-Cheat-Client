package com.potatotv.pacc.detection.server.behavior;

import java.util.Locale;
import java.util.Objects;

/**
 * LSTM 权重来源标注（技术文档 §5.4 U-07）。
 *
 * <p>为什么需要这个：权重文件本身不带来源信息，代码里也看不出一组数是训练出来的还是随机初始化的。
 * 两者在检测中的可信度完全不同——随机权重没有判别力，偶尔给出高概率纯属巧合。如果不标注，
 * 运维看到「ML 命中」会以为是模型结论，这就是把噪声当证据。
 *
 * <p>因此约定：只有 {@link Source#TRAINED} 的权重才允许产出 ML 证据；
 * {@link Source#HEURISTIC_DEFAULT} 的权重永远不出证，只在状态行里说明「当前为启发式默认权重」。
 * {@link Source#UNSPECIFIED} 保留给单元测试与显式加载外部权重的调用方，不作为线上默认。
 *
 * @param source         权重来源
 * @param version        权重版本，便于与训练记录对齐（随机权重用 {@code seed:<seed>}）
 * @param trainingRecord 训练记录标识；启发式默认权重为空串，表示**没有**训练记录
 */
public record WeightProvenance(Source source, String version, String trainingRecord) {

    public enum Source {
        /** 用真实标注回放数据训练得到，版本与训练记录可追溯。 */
        TRAINED,
        /** 随机初始化的启发式默认权重，无判别力，不得出证。 */
        HEURISTIC_DEFAULT,
        /** 未标注来源（调用方自行负责），不作为线上默认。 */
        UNSPECIFIED
    }

    public WeightProvenance {
        Objects.requireNonNull(source, "source");
        version = version == null ? "" : version;
        trainingRecord = trainingRecord == null ? "" : trainingRecord;
    }

    /** 随机权重的来源标注。 */
    public static WeightProvenance heuristicDefault(long seed) {
        return new WeightProvenance(Source.HEURISTIC_DEFAULT, "seed:" + seed, "");
    }

    /** 训练权重的来源标注；训练记录为空视为不合规，直接拒绝。 */
    public static WeightProvenance trained(String version, String trainingRecord) {
        if (trainingRecord == null || trainingRecord.isBlank()) {
            throw new IllegalArgumentException("训练权重必须带训练记录标识，否则无法追溯");
        }
        return new WeightProvenance(Source.TRAINED, version, trainingRecord);
    }

    /** 调用方自行负责来源的标注，仅用于测试与显式外部加载。 */
    public static WeightProvenance unspecified() {
        return new WeightProvenance(Source.UNSPECIFIED, "unspecified", "");
    }

    /** 是否处于降级状态：降级权重不得产出 ML 证据。 */
    public boolean degraded() {
        return source == Source.HEURISTIC_DEFAULT;
    }

    /** 单行标注，附在证据 detail 与启动状态里，便于回溯「这条结论用了哪版权重」。 */
    public String tag() {
        String base = "weights=" + source.name().toLowerCase(Locale.ROOT) + " v=" + version;
        return trainingRecord.isEmpty() ? base : base + " record=" + trainingRecord;
    }
}
