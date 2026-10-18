package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.packet.BlockProbe;
import com.potatotv.pacc.server.core.packet.PacketTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 穿墙检测（Phase，技术文档 §5.1 U-01 验收项之一）。
 *
 * <p>判定口径：玩家做出的移动包里，脚部与头部所在方块同时是实心方块，说明位置落在碰撞体内部。
 * 单包不算数——区块刚加载、传送瞬间、载具里都会出现瞬时错位，所以要求连续
 * {@value #REQUIRED_STRIKES} 次采样都命中才出证。
 *
 * <p>这只是「碰撞一致性」的粗口径：真正的 Phase 还需要服务端碰撞盒与移动路径求交，那要世界副本
 * 有方块数据。本检查只回答「玩家是否停在实心方块里」，够抓常见的穿墙脚本，也不会把区块未加载
 * 当成作弊——{@link BlockProbe} 给不出数据时返回 {@link BlockProbe.SolidState#UNKNOWN}，本检查
 * 直接跳过该次采样并清零连击计数。
 *
 * <p>采样限流：每玩家每 {@value #SAMPLE_INTERVAL_MS} 毫秒最多查一次方块，避免在主线程上按包频
 * 反复访问世界。探针为 {@code null}（平台给不出方块数据）时本检查整体关闭。
 */
public final class PhaseCheck implements ServerCheck {

    /** 每玩家采样间隔（毫秒）。 */
    static final long SAMPLE_INTERVAL_MS = 250L;

    /** 连续命中次数阈值：单次命中不出证。公开是为了让拉普拉斯侧锚定注入幅度。 */
    public static final int REQUIRED_STRIKES = 2;

    /** 玩家脚部到头顶的判定高度（格），取玩家站立高度 1.8。 */
    private static final double PLAYER_HEIGHT = 1.8;

    private final BlockProbe probe;
    private final ConcurrentHashMap<String, Long> lastSampleMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> strikes = new ConcurrentHashMap<>();

    public PhaseCheck(BlockProbe probe) {
        this.probe = probe;
    }

    /** 平台是否具备穿墙检测能力（探针可用）。 */
    public boolean available() {
        return probe != null;
    }

    @Override
    public CheckType type() {
        return CheckType.PACKET;
    }

    @Override
    public List<DetectionResult> onPacket(PacketView packet) {
        if (probe == null || packet == null || !PacketTypes.carriesPosition(packet.packetType())) {
            return List.of();
        }
        if (!packet.has(PacketTypes.FIELD_X)
                || !packet.has(PacketTypes.FIELD_Y)
                || !packet.has(PacketTypes.FIELD_Z)) {
            return List.of();
        }
        String playerId = packet.playerId();
        long now = packet.timestampMs();
        Long last = lastSampleMs.get(playerId);
        if (last != null && now - last < SAMPLE_INTERVAL_MS) {
            return List.of();
        }
        lastSampleMs.put(playerId, now);

        int blockX = (int) Math.floor(packet.num(PacketTypes.FIELD_X, 0));
        int blockZ = (int) Math.floor(packet.num(PacketTypes.FIELD_Z, 0));
        double feetY = packet.num(PacketTypes.FIELD_Y, 0);
        int feet = (int) Math.floor(feetY);
        int head = (int) Math.floor(feetY + PLAYER_HEIGHT);

        BlockProbe.SolidState feetState = probe.stateAt(playerId, blockX, feet, blockZ);
        BlockProbe.SolidState headState = probe.stateAt(playerId, blockX, head, blockZ);
        if (feetState == BlockProbe.SolidState.UNKNOWN || headState == BlockProbe.SolidState.UNKNOWN) {
            // 拿不到方块数据（区块未加载等）：清掉连击，不做任何结论。
            strikes.remove(playerId);
            return List.of();
        }
        if (feetState != BlockProbe.SolidState.SOLID && headState != BlockProbe.SolidState.SOLID) {
            strikes.remove(playerId);
            return List.of();
        }

        int count = strikes.merge(playerId, 1, Integer::sum);
        if (count < REQUIRED_STRIKES) {
            return List.of();
        }
        strikes.remove(playerId);
        List<DetectionResult> results = new ArrayList<>(1);
        results.add(DetectionResult.violation(CheckType.PACKET, "PHASE", 80,
                String.format(Locale.ROOT,
                        "位置落在实心方块内 脚部=(%d,%d,%d) 头部=(%d,%d,%d) 连续命中=%d次",
                        blockX, feet, blockZ, blockX, head, blockZ, count),
                now));
        return results;
    }

    @Override
    public void reset(String playerId) {
        lastSampleMs.remove(playerId);
        strikes.remove(playerId);
    }
}
