package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.server.mining.OreDiscoveryStats;
import com.potatotv.pacc.detection.server.mining.XRayAnalyzer;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;

import java.util.List;
import java.util.Optional;

/**
 * X-Ray 挖矿统计检查（技术文档 §5.3 U-04）。
 *
 * <p>数据来源全部是服务端认账的事实：方块破坏事件给出矿石种类，移动事件给出探索距离。
 * 客户端伪造不了这两者，所以这个检查不依赖任何包级信息——PacketEvents 没装也能工作。
 *
 * <p>判定逻辑在 {@link XRayAnalyzer}，本类只负责把两类事件接进统计并在命中时转成检测结果。
 * 样本量门槛与双条件（稀有矿占比 + 每矿探索距离）都在分析器里，避免在这里再散一份阈值。
 */
public final class XRayCheck implements ServerCheck, MoveAware {

    private final OreDiscoveryStats stats;
    private final XRayAnalyzer analyzer;

    public XRayCheck() {
        this(new OreDiscoveryStats(), new XRayAnalyzer());
    }

    /** 允许注入统计与判定器，便于测试与后续接入真实校准数据。 */
    public XRayCheck(OreDiscoveryStats stats, XRayAnalyzer analyzer) {
        this.stats = stats;
        this.analyzer = analyzer;
    }

    @Override
    public CheckType type() {
        return CheckType.INTERACTION;
    }

    @Override
    public void onMoveObserved(PlayerPosition from, PlayerPosition to) {
        if (from == null || to == null) {
            return;
        }
        // 只算水平位移：上下矿道与跳落不该算成探索路径
        double dx = to.pos().x() - from.pos().x();
        double dz = to.pos().z() - from.pos().z();
        stats.recordTravel(to.playerId(), Math.sqrt(dx * dx + dz * dz));
    }

    @Override
    public List<DetectionResult> onBlockBreak(
            String playerId, String blockType, Position pos, long timestampMs) {
        stats.recordOre(playerId, blockType);
        Optional<Evidence> evidence = analyzer.evaluate(stats.profile(playerId));
        return evidence
                .map(ev -> List.of(DetectionResult.violation(CheckType.INTERACTION, ev.detectorId(),
                        (int) Math.round(ev.confidence() * 100), ev.detail(), timestampMs)))
                .orElseGet(List::of);
    }

    @Override
    public void reset(String playerId) {
        stats.reset(playerId);
    }
}
