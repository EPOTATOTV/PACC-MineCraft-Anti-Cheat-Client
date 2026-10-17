package com.potatotv.pacc.server.core.packet;

import com.potatotv.pacc.server.core.model.ServerType;

import java.util.Set;

/**
 * 单个平台的包级检测能力声明（技术文档 §5.1 U-03：能力差异显式声明）。
 *
 * <p>包级检测能到什么精度，取决于平台给不给真实数据包。Bukkit 系接了 PacketEvents 才有真实包；
 * Nukkit / AllayMC / Geyser 只有事件模型，Timer / Blink / Phase 这类依赖包到达时序与包内容的
 * 判定做不了。与其让运维从「没告警」猜「没作弊」，不如在启动日志与文档里把每个平台能做什么
 * 写明。
 *
 * @param platform            平台类型
 * @param realPacketSource    是否有真实数据包来源（PacketEvents 等）
 * @param source              数据包来源标识，写清是真实包还是平台事件合成
 * @param packetTypes         该平台能喂进检测引擎的归一化包类型集合
 * @param phaseCheckAvailable 穿墙检测是否可用（需要可用的 {@link BlockProbe}）
 * @param note                能力边界说明，运维排查时看这一行
 */
public record PacketCapability(
        ServerType platform,
        boolean realPacketSource,
        String source,
        Set<String> packetTypes,
        boolean phaseCheckAvailable,
        String note) {

    public PacketCapability {
        packetTypes = packetTypes == null ? Set.of() : Set.copyOf(packetTypes);
    }

    /** 覆盖的真实数据包类型数量，与 {@link PacketTypes#REAL_PACKET_SOURCE_TYPES} 对照。 */
    public int coveredRealTypeCount() {
        int count = 0;
        for (String type : packetTypes) {
            if (PacketTypes.REAL_PACKET_SOURCE_TYPES.contains(type)) {
                count++;
            }
        }
        return count;
    }

    /** 是否覆盖了全部真实数据包类型（接入 PacketEvents 后应当为 true）。 */
    public boolean coversAllRealTypes() {
        return coveredRealTypeCount() == PacketTypes.REAL_PACKET_SOURCE_TYPES.size();
    }

    /** 单行摘要，供启动日志与能力矩阵表使用。 */
    public String describe() {
        return platform + " 包源=" + source
                + " 真实包=" + (realPacketSource ? "是" : "否")
                + " 覆盖类型=" + coveredRealTypeCount() + "/" + PacketTypes.REAL_PACKET_SOURCE_TYPES.size()
                + " 穿墙检测=" + (phaseCheckAvailable ? "可用" : "不可用")
                + "；" + note;
    }
}
