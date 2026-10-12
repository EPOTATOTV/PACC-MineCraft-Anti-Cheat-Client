package com.potatotv.pacc.detection.server.packet;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;

/**
 * 包序检测（技术文档 §九：协议异常 / 包篡改）。
 *
 * <p>按（玩家, 包类型）维护最近 sequence；同类型包 sequence 倒退（小于已记录值）
 * 说明客户端在重放 / 篡改序列，或中间存在 PingSpoof 类工具伪造时序，出证据 0.6。
 */
public final class PacketOrder {

    public static final String DETECTOR_ID = "packet-order";

    /** 乱序固定置信。 */
    private static final double CONFIDENCE = 0.6;

    /** playerId → (packetType → 最近 sequence)。 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> lastSequences =
            new ConcurrentHashMap<>();

    /**
     * 登记并检查一个包的 sequence：倒退即出证（保留历史最大值，连续倒退会连续出证）。
     */
    public Optional<Evidence> check(String playerId, String packetType, long sequence) {
        ConcurrentHashMap<String, Long> byType =
                lastSequences.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        Long last = byType.get(packetType);
        if (last != null && sequence < last) {
            String detail = String.format(Locale.ROOT,
                    "sequence 倒退 type=%s last=%d current=%d", packetType, last, sequence);
            return Optional.of(Evidence.of(DETECTOR_ID, Layer.NETWORK, CONFIDENCE, detail));
        }
        byType.merge(packetType, sequence, Math::max);
        return Optional.empty();
    }

    /** 清空玩家状态（重连时调用）。 */
    public void reset(String playerId) {
        lastSequences.remove(playerId);
    }
}
