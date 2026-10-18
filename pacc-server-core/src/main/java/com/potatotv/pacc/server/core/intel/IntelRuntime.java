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
import com.potatotv.pacc.detection.server.behavior.WeightProvenance;
import com.potatotv.pacc.detection.server.combat.AttackPattern;
import com.potatotv.pacc.detection.server.combat.HitboxValidator;
import com.potatotv.pacc.detection.server.combat.KnockbackPredictor;
import com.potatotv.pacc.detection.server.combat.ReachCheck;
import com.potatotv.pacc.detection.server.combat.RotationAnalyzer;
import com.potatotv.pacc.detection.server.packet.BacktrackAnalyzer;
import com.potatotv.pacc.detection.server.packet.BrandValidator;
import com.potatotv.pacc.detection.server.packet.PacketOrder;
import com.potatotv.pacc.detection.server.packet.PacketTiming;
import com.potatotv.pacc.detection.server.packet.TransactionSync;
import com.potatotv.pacc.detection.server.perf.PerformanceCalibrator;
import com.potatotv.pacc.detection.server.prediction.LatencyCompensator;
import com.potatotv.pacc.detection.server.prediction.PredictionEngine;
import com.potatotv.pacc.detection.server.prediction.SetbackController;
import com.potatotv.pacc.detection.server.prediction.WorldReplica;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.packet.TransactionSender;

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

    /** 每世界一份压缩世界副本（按世界名共享，玩家通过 playerWorlds 绑定）。 */
    private final ConcurrentHashMap<String, WorldReplica> worlds = new ConcurrentHashMap<>();

    /** 玩家 → 世界名绑定；没有绑定时预测引擎按「无世界数据」处理。 */
    private final ConcurrentHashMap<String, String> playerWorlds = new ConcurrentHashMap<>();

    private final FusionEngine fusion = new FusionEngine();
    private final CorrelationEngine correlation = new CorrelationEngine();
    private final PredictionEngine predictionEngine;
    private final StatOutlier statOutlier = new StatOutlier();
    private final PatternCluster patternCluster = new PatternCluster();
    /** 内置启发式权重的固定种子（无训练数据时的降级默认值）。 */
    private static final long ML_WEIGHT_SEED = 20260101L;

    /**
     * ML 管线。当前用的是固定种子的随机初始化权重，来源显式标注为
     * {@link WeightProvenance.Source#HEURISTIC_DEFAULT}，{@code classify} 会直接拒绝出证——
     * 随机权重没有判别力，不能当模型结论（§5.4 U-07）。拿到真实标注训练权重后，用
     * {@code WeightProvenance.trained(...)} 替换即可，其余代码不用动。
     */
    private final MLDetector mlDetector = new MLDetector(3, 16,
            LstmWeights.random(3, 16, ML_WEIGHT_SEED), WeightProvenance.heuristicDefault(ML_WEIGHT_SEED));
    private final ReachCheck reachCheck = new ReachCheck();
    private final AttackPattern attackPattern = new AttackPattern();
    private final RotationAnalyzer rotationAnalyzer = new RotationAnalyzer();
    private final KnockbackPredictor knockbackPredictor = new KnockbackPredictor();
    private final HitboxValidator hitboxValidator = new HitboxValidator();
    private final PacketTiming packetTiming = new PacketTiming();
    private final PacketOrder packetOrder = new PacketOrder();
    private final BacktrackAnalyzer backtrackAnalyzer = new BacktrackAnalyzer();
    private final TransactionSync transactionSync = new TransactionSync();
    private final BrandValidator brandValidator = new BrandValidator();
    private final LatencyCompensator latencyCompensator = new LatencyCompensator();
    private final SetbackController setbackController = new SetbackController();
    /** 实测刻长 / TPS 来源，包时序检测按它做阈值归一化（§5.3 U-05）。 */
    private final PerformanceCalibrator calibrator = new PerformanceCalibrator();

    /** 每玩家上一个移动起始位置（速度估算用）。 */
    private final ConcurrentHashMap<String, Position> lastPositions = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Deque<Evidence>> windows = new ConcurrentHashMap<>();

    public IntelRuntime() {
        this.predictionEngine = new PredictionEngine(this::worldOf);
    }

    /**
     * 证据链（技术文档 §8.2 防篡改）：每次融合告警把证据载荷追加进 HMAC 链式哈希。
     * 未配置密钥时为 null，链式哈希关闭（默认行为不变）。
     */
    private volatile EvidenceChain evidenceChain;

    /**
     * 事务发包接缝。为 null 表示平台发不出事务包，此时不做延迟估计也不判 Disabler——
     * 拿编造的 id 记账会让 ack 率恒为 0，把所有正常玩家判成 Disabler。
     */
    private volatile TransactionSender transactionSender;

    /** 装配事务发包接缝（由适配层在启动时注入；不注入即视为平台不支持事务）。 */
    public void attachTransactionSender(TransactionSender sender) {
        this.transactionSender = sender;
    }

    /**
     * 发一个事务并登记真实 id。
     *
     * @return 是否真的发出并登记成功；{@code false} 时调用方不应做任何延迟/ack 判定
     */
    public boolean requestTransaction(String playerId) {
        TransactionSender sender = transactionSender;
        if (sender == null) {
            return false;
        }
        int id = sender.send(playerId);
        return id >= 0 && latencyCompensator.registerSent(id);
    }

    /** 当前是否具备真实事务发包能力。 */
    public boolean transactionsSupported() {
        return transactionSender != null;
    }

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

    /** 性能自适应校准器（§5.3 U-05）。 */
    public PerformanceCalibrator calibrator() {
        return calibrator;
    }

    public SetbackController setbackController() {
        return setbackController;
    }

    public StatOutlier statOutlier() {
        return statOutlier;
    }

    /** P-05：Backtrack / FakeLag 包滞留与时序重放检测。 */
    public BacktrackAnalyzer backtrackAnalyzer() {
        return backtrackAnalyzer;
    }

    public PatternCluster patternCluster() {
        return patternCluster;
    }

    public MLDetector mlDetector() {
        return mlDetector;
    }

    /** 绑定玩家所在世界；平台每次移动事件都会刷新。 */
    public void bindPlayerWorld(String playerId, String worldName) {
        if (playerId == null || worldName == null || worldName.isBlank()) {
            return;
        }
        playerWorlds.put(playerId, worldName);
    }

    /** 按世界名取共享副本（不存在则创建空世界）。 */
    public WorldReplica worldByName(String worldName) {
        return worlds.computeIfAbsent(worldName, unused -> WorldReplica.empty());
    }

    /**
     * 玩家当前世界副本；没有绑定或世界尚未创建时返回 {@code null}。
     *
     * <p>预测检查必须用这个而不是创建空世界：空世界没有碰撞信息，
     * 会让 {@code PredictionEngine} 误以为「有世界数据」而切到严格候选匹配。
     */
    public WorldReplica existingWorld(String playerId) {
        String worldName = playerWorlds.get(playerId);
        return worldName == null ? null : worlds.get(worldName);
    }

    /** 兼容入口：按玩家取世界副本（未绑定时返回 null，不再隐式创建）。 */
    public WorldReplica worldOf(String playerId) {
        return existingWorld(playerId);
    }

    /** 平台方块变更入口：写入共享世界副本。 */
    public void applyBlockChange(String worldName, int x, int y, int z, String material) {
        if (worldName == null || worldName.isBlank()) {
            return;
        }
        worldByName(worldName).applyBlockChange(x, y, z, material);
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
        playerWorlds.remove(playerId);
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
        backtrackAnalyzer.reset(playerId);
    }

    private static void pruneLocked(Deque<Evidence> window) {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        while (!window.isEmpty()
                && (window.peekFirst().timestampMs() < cutoff || window.size() > WINDOW_MAX)) {
            window.pollFirst();
        }
    }
}
