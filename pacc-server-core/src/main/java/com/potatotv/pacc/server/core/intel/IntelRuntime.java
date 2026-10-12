package com.potatotv.pacc.server.core.intel;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.EvidenceBatch;
import com.potatotv.pacc.detection.fusion.EvidenceChain;
import com.potatotv.pacc.detection.fusion.FusionEngine;
import com.potatotv.pacc.detection.server.behavior.CorrelationEngine;
import com.potatotv.pacc.detection.server.behavior.StatOutlier;
import com.potatotv.pacc.detection.server.behavior.PatternCluster;
import com.potatotv.pacc.detection.server.behavior.LstmWeights;
import com.potatotv.pacc.detection.server.behavior.MLDetector;
import com.potatotv.pacc.detection.server.combat.AttackPattern;
import com.potatotv.pacc.detection.server.combat.HitboxValidator;
import com.potatotv.pacc.detection.server.combat.KnockbackPredictor;
import com.potatotv.pacc.detection.server.combat.ReachCheck;
import com.potatotv.pacc.detection.server.combat.RotationAnalyzer;
import com.potatotv.pacc.detection.server.packet.BrandValidator;
import com.potatotv.pacc.detection.server.packet.PacketOrder;
import com.potatotv.pacc.detection.server.packet.PacketTiming;
import com.potatotv.pacc.detection.server.packet.TransactionSync;
import com.potatotv.pacc.detection.server.prediction.LatencyCompensator;
import com.potatotv.pacc.detection.server.prediction.PredictionEngine;
import com.potatotv.pacc.detection.server.prediction.SetbackController;
import com.potatotv.pacc.detection.server.prediction.WorldReplica;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.Position;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intel 检查共享运行时（技术文档 §4.2 server 包 / §8.1 融合）。
 *
 * <p>四类 intel 检查（预测 / 战斗 / 数据包 / 行为）共享同一份运行时：
 * 每玩家世界副本、速度跟踪、延迟补偿、回撤控制，以及一个 10 秒滚动的证据窗口。
 * 窗口内证据先过 {@link CorrelationEngine}（单维度不告警），多维度印证再过
 * {@link FusionEngine} 出融合结论——服务端默认仅告警，处置由插件配置决定。
 */
public final class IntelRuntime {

    /** 证据窗口时长（毫秒）。 */
    static final long WINDOW_MS = 10_000L;

    /** 证据窗口容量上限（防刷屏）。 */
    static final int WINDOW_MAX = 64;

    /** 每玩家压缩世界副本（世界变更由平台适配层喂入，缺省为空世界=无碰撞）。 */
    private final ConcurrentHashMap<String, WorldReplica> worlds = new ConcurrentHashMap<>();

    private final FusionEngine fusion = new FusionEngine();
    private final CorrelationEngine correlation = new CorrelationEngine();
    private final PredictionEngine predictionEngine = new PredictionEngine(worlds);
    private final StatOutlier statOutlier = new StatOutlier();
    private final PatternCluster patternCluster = new PatternCluster();
    /** ML 管线：随机权重仅保证可运行，0.95 置信门会拦下随机权重输出；生产用云端训练权重替换。 */
    private final MLDetector mlDetector = new MLDetector(3, 16, LstmWeights.random(3, 16, 20260101L));
    private final ReachCheck reachCheck = new ReachCheck();
    private final AttackPattern attackPattern = new AttackPattern();
    private final RotationAnalyzer rotationAnalyzer = new RotationAnalyzer();
    private final KnockbackPredictor knockbackPredictor = new KnockbackPredictor();
    private final HitboxValidator hitboxValidator = new HitboxValidator();
    private final PacketTiming packetTiming = new PacketTiming();
    private final PacketOrder packetOrder = new PacketOrder();
    private final TransactionSync transactionSync = new TransactionSync();
    private final BrandValidator brandValidator = new BrandValidator();
    private final LatencyCompensator latencyCompensator = new LatencyCompensator();
    private final SetbackController setbackController = new SetbackController();

    /** 每玩家上一个移动起始位置（速度估算用）。 */
    private final ConcurrentHashMap<String, Position> lastPositions = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Deque<Evidence>> windows = new ConcurrentHashMap<>();

    /**
     * 证据链（技术文档 §8.2 防篡改）：每次融合告警把证据载荷追加进 HMAC 链式哈希。
     * 未配置密钥时为 null，链式哈希关闭（默认行为不变）。
     */
    private volatile EvidenceChain evidenceChain;

    /** 启用证据链（密钥 ≥16 字节；空白则忽略）。 */
    public void enableEvidenceChain(byte[] hmacKey) {
        if (hmacKey == null || hmacKey.length < 16) {
            return;
        }
        this.evidenceChain = new EvidenceChain(hmacKey);
    }

    /** 证据链是否启用。 */
    public boolean evidenceChainEnabled() {
        return evidenceChain != null;
    }

    /** 证据链当前长度（未启用为 0）。 */
    public int evidenceChainLength() {
        EvidenceChain chain = evidenceChain;
        return chain == null ? 0 : chain.length();
    }

    /** 证据链尾 MAC（未启用为空串）。 */
    public String evidenceChainTailMac() {
        EvidenceChain chain = evidenceChain;
        return chain == null ? "" : chain.tailMac();
    }

    /** 证据链条目只读快照（未启用为空列表）。 */
    public List<EvidenceChain.Entry> evidenceChainEntries() {
        EvidenceChain chain = evidenceChain;
        return chain == null ? List.of() : chain.entries();
    }

    /** 把一条证据载荷追加进证据链，返回链尾 MAC（未启用返回空串）。 */
    public String sealEvidence(String payload) {
        EvidenceChain chain = evidenceChain;
        if (chain == null) {
            return "";
        }
        return chain.append(payload).mac();
    }

    public PredictionEngine predictionEngine() {
        return predictionEngine;
    }

    public ReachCheck reachCheck() {
        return reachCheck;
    }

    public AttackPattern attackPattern() {
        return attackPattern;
    }

    public RotationAnalyzer rotationAnalyzer() {
        return rotationAnalyzer;
    }

    public KnockbackPredictor knockbackPredictor() {
        return knockbackPredictor;
    }

    public HitboxValidator hitboxValidator() {
        return hitboxValidator;
    }

    public PacketTiming packetTiming() {
        return packetTiming;
    }

    public PacketOrder packetOrder() {
        return packetOrder;
    }

    public TransactionSync transactionSync() {
        return transactionSync;
    }

    public BrandValidator brandValidator() {
        return brandValidator;
    }

    public LatencyCompensator latencyCompensator() {
        return latencyCompensator;
    }

    public SetbackController setbackController() {
        return setbackController;
    }

    public StatOutlier statOutlier() {
        return statOutlier;
    }

    public PatternCluster patternCluster() {
        return patternCluster;
    }

    public MLDetector mlDetector() {
        return mlDetector;
    }

    /** 玩家世界副本（不存在则创建空世界）。 */
    public WorldReplica worldOf(String playerId) {
        return worlds.computeIfAbsent(playerId, unused -> WorldReplica.empty());
    }

    /** 记录一条证据到玩家窗口。 */
    public void record(String playerId, Evidence evidence) {
        Deque<Evidence> window = windows.computeIfAbsent(playerId, unused -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(evidence);
            pruneLocked(window);
        }
    }

    /**
     * 速度估算：当前起始位置相对上一个起始位置的位移。
     * 首帧返回 null（无速度历史，物理预测跳过——用零速度预测会把任何主动移动误判成偏移）。
     */
    Position nextVelocity(String playerId, Position currentFrom) {
        Position last = lastPositions.put(playerId, currentFrom);
        if (last == null) {
            return null;
        }
        return new Position(currentFrom.x() - last.x(), currentFrom.y() - last.y(), currentFrom.z() - last.z());
    }

    /** model.Position → 引擎 prediction.Position（两者字段同构，包内转换口）。 */
    static com.potatotv.pacc.detection.server.prediction.Position toEnginePos(Position p) {
        return new com.potatotv.pacc.detection.server.prediction.Position(p.x(), p.y(), p.z());
    }

    /**
     * 融合判定：窗口证据先过相关性（≥2 个检测器同时异常才有意义），
     * 再过六层融合（§8.1）。返回要上报的额外结果（无印证或 CLEAN 为空）。
     *
     * @param latencyMs 当前估算延迟（网络异常降权用）
     * @param creative  是否创造/旁观（物理预测豁免）
     */
    Optional<DetectionResult> fuse(String playerId, CheckType type, long latencyMs, boolean creative) {
        List<Evidence> snapshot;
        Deque<Evidence> window = windows.get(playerId);
        if (window == null) {
            return Optional.empty();
        }
        synchronized (window) {
            pruneLocked(window);
            snapshot = new ArrayList<>(window);
        }
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        Optional<Evidence> merged = correlation.correlate(playerId, snapshot);
        if (merged.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> context = creative ? Map.of("creative", "true") : Map.of();
        EvidenceBatch batch = new EvidenceBatch(
                playerId, snapshot, latencyMs > 300, false, context);
        FusionEngine.FusionResult result = fusion.evaluateDetailed(batch);
        if (result.verdict() == com.potatotv.pacc.detection.fusion.Verdict.CLEAN) {
            return Optional.empty();
        }
        String detail = "多层证据融合: " + merged.get().detail();
        // 证据链（§8.2）：把本次融合载荷封进 HMAC 链，链尾 MAC 附在 detail 供后端校验与留档
        String mac = sealEvidence(playerId + "|" + type + "|" + detail);
        if (!mac.isEmpty()) {
            detail = detail + " evidenceMac=" + mac;
        }
        return Optional.of(DetectionResult.violation(
                type, "fusion-" + result.verdict().name().toLowerCase(),
                (int) Math.round(result.score() * 100), detail, System.currentTimeMillis()));
    }

    /** 玩家下线：清空所有共享状态。 */
    public void clear(String playerId) {
        worlds.remove(playerId);
        lastPositions.remove(playerId);
        windows.remove(playerId);
        statOutlier.reset(playerId);
        patternCluster.reset(playerId);
        attackPattern.reset(playerId);
        rotationAnalyzer.reset(playerId);
        knockbackPredictor.reset(playerId);
        reachCheck.reset(playerId);
        packetTiming.reset(playerId);
        packetOrder.reset(playerId);
    }

    private static void pruneLocked(Deque<Evidence> window) {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        while (!window.isEmpty()
                && (window.peekFirst().timestampMs() < cutoff || window.size() > WINDOW_MAX)) {
            window.pollFirst();
        }
    }
}
