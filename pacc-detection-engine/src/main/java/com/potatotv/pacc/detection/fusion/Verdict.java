package com.potatotv.pacc.detection.fusion;

/**
 * 融合判定结论（技术文档 §8.1 分档）。
 *
 * <p>硬约束：玩家端模式不封禁——CONFIRMED 在玩家端映射为红屏警告 + 本地留痕 + 远程查端；
 * 在服务端插件模式默认仅告警，处置动作由运维配置决定。
 */
public enum Verdict {

    /** 正常：无足够证据。 */
    CLEAN("正常"),

    /** 可疑：观察 + 记录。 */
    SUSPECT("可疑：观察+记录"),

    /** 确认：红屏警告 + 远程查端（玩家端模式）；服务端默认仅告警。 */
    CONFIRMED("确认：红屏+查端");

    private final String description;

    Verdict(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
