package com.potatotv.pacc.detection.fusion;

/**
 * 检测纵深层（技术文档 §4.1 双端六层）。
 *
 * <p>每层有一个默认融合权重，权重反映该层证据的可靠程度：内存篡改（L3）与
 * 注入模块（L2）是硬证据，权重最高；网络层（L5）受环境干扰大，权重最低。
 */
public enum Layer {

    /** L1 进程层：作弊进程名 / 窗口指纹 / 签名哈希。 */
    PROCESS("L1", 0.85),

    /** L2 模块层：注入 DLL / Mod 列表 / 线程注入痕迹。 */
    MODULE("L2", 0.90),

    /** L3 内存层：内存工具扫描行为 / 游戏进程内存篡改。 */
    MEMORY("L3", 1.00),

    /** L4 输入层：宏点击 / 连点器 / AutoClicker 输入分布。 */
    INPUT("L4", 0.70),

    /** L5 网络层：代理工具 / 数据包修改 / 协议异常。 */
    NETWORK("L5", 0.60),

    /** L6 行为层：物理预测 / 战斗分析 / 行为统计 / AI 检测。 */
    BEHAVIOR("L6", 0.75);

    private final String code;
    private final double defaultWeight;

    Layer(String code, double defaultWeight) {
        this.code = code;
        this.defaultWeight = defaultWeight;
    }

    /** 层代号，如 {@code "L3"}。 */
    public String code() {
        return code;
    }

    /** 默认融合权重（0-1）。 */
    public double defaultWeight() {
        return defaultWeight;
    }
}
