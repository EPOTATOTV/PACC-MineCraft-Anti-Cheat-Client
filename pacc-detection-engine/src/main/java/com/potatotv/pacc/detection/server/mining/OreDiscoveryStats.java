package com.potatotv.pacc.detection.server.mining;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端矿石发现率统计（技术文档 §5.3 U-04 / §7.4 / P-04）。
 *
 * <p>只统计服务端**认账**的挖掘：方块破坏事件由服务端发出，客户端伪造不了。
 * 每个玩家维护矿石总数、稀有矿数与水平移动距离，交给 {@link XRayAnalyzer} 判比值。
 *
 * <p>P-04 追加视角相关性：挖矿瞬间的 yaw 与「玩家→矿石」方向的夹角、yaw 分布熵、
 * 会话路径直线度。它们和原有的稀有矿占比 / 每矿探索距离交叉验证，单独一项不出证。
 *
 * <p>移动距离按「相邻两次位置的水平位移」累加，并丢弃单次超过
 * {@value #MAX_SINGLE_MOVE_BLOCKS} 格的跳变——传送、回城、载具瞬移不是探索路径，
 * 计进去会把分母撑大，反而让 X-Ray 玩家看起来正常。
 *
 * <p>线程安全：每玩家一个 {@code synchronized} 计数器，事件来自主线程与包线程。
 */
public final class OreDiscoveryStats {

    /** 单次移动计入探索路径的上限（格）；超过视为传送/瞬移，不计入。 */
    public static final double MAX_SINGLE_MOVE_BLOCKS = 32.0;

    /** yaw 熵分箱宽度（度）。 */
    private static final double YAW_BUCKET_DEG = 30.0;

    /** 视角样本上限：长局也只保留最近这批，避免列表无限增长拖慢 TPS。 */
    public static final int MAX_VIEW_SAMPLES = 512;

    /** 方向判定所需的最小单步位移（格），过滤原地抖动。 */
    private static final double MIN_DIRECTION_STEP = 0.1;

    /** 相邻两段路径夹角超过该值算一次转向（度）。 */
    private static final double TURN_ANGLE_DEG = 45.0;

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    /** 记录挖到一块矿石；非矿石方块直接忽略。无坐标时只进基础统计。 */
    public void recordOre(String playerId, String blockType) {
        recordOre(playerId, blockType, Double.NaN, Double.NaN);
    }

    /**
     * 记录挖到一块矿石，并带上矿石坐标用于视角相关性统计。
     *
     * @param oreX 矿石水平 X；未知时传 {@link Double#NaN}
     * @param oreZ 矿石水平 Z；未知时传 {@link Double#NaN}
     */
    public void recordOre(String playerId, String blockType, double oreX, double oreZ) {
        OreType type = OreType.fromBlock(blockType);
        if (type == null) {
            return;
        }
        Counter counter = counters.computeIfAbsent(playerId, unused -> new Counter());
        counter.recordOre(type, oreX, oreZ);
    }

    /** 记录一次水平移动（格）。 */
    public void recordTravel(String playerId, double horizontalDistance) {
        if (horizontalDistance <= 0 || horizontalDistance > MAX_SINGLE_MOVE_BLOCKS) {
            return;
        }
        counters.computeIfAbsent(playerId, unused -> new Counter()).recordTravel(horizontalDistance);
    }

    /**
     * 记录玩家当前位置与视角。用于「玩家→矿石」方向计算和路径直线度。
     *
     * @param x   水平 X
     * @param z   水平 Z
     * @param yaw 玩家 yaw（Minecraft 约定：0=+Z，90=-X）
     */
    public void recordPosition(String playerId, double x, double z, float yaw) {
        if (Double.isNaN(x) || Double.isNaN(z) || Double.isInfinite(x) || Double.isInfinite(z)) {
            return;
        }
        counters.computeIfAbsent(playerId, unused -> new Counter()).recordPosition(x, z, yaw);
    }

    /** 取玩家当前矿石画像快照；没有记录时返回全零。 */
    public OreProfile profile(String playerId) {
        Counter counter = counters.get(playerId);
        return counter == null ? new OreProfile(0, 0, 0) : counter.snapshot();
    }

    /** 取玩家视角相关性画像；没有视角样本时返回 {@link ViewCorrelationProfile#empty()}。 */
    public ViewCorrelationProfile viewProfile(String playerId) {
        Counter counter = counters.get(playerId);
        return counter == null ? ViewCorrelationProfile.empty() : counter.viewSnapshot();
    }

    /** 取玩家路径画像；没有移动数据时返回空画像。 */
    public PathProfile pathProfile(String playerId) {
        Counter counter = counters.get(playerId);
        return counter == null ? PathProfile.empty() : counter.pathSnapshot();
    }

    /** 会话结束或重连时清空，避免把上一局的数据算进这一局。 */
    public void reset(String playerId) {
        counters.remove(playerId);
    }

    /** 单玩家计数器。 */
    private static final class Counter {

        private int totalOres;
        private int rareOres;
        private double travelledBlocks;

        private boolean hasPosition;
        private double startX;
        private double startZ;
        private double lastX;
        private double lastZ;
        private float lastYaw;

        private final ArrayDeque<Double> alignments = new ArrayDeque<>(MAX_VIEW_SAMPLES);
        private final ArrayDeque<Double> yawSamples = new ArrayDeque<>(MAX_VIEW_SAMPLES);

        private boolean hasDirection;
        private double lastDirectionDeg;
        private int directionSamples;
        private int turns;
        private double straightRun;
        private double longestStraightRun;

        private OreProfile cachedOreProfile;
        private ViewCorrelationProfile cachedViewProfile;
        private PathProfile cachedPathProfile;

        private synchronized void recordOre(OreType type, double oreX, double oreZ) {
            totalOres++;
            if (type.rare()) {
                rareOres++;
            }
            if (hasPosition && !Double.isNaN(oreX) && !Double.isNaN(oreZ)) {
                addBounded(alignments, alignmentDeg(lastX, lastZ, lastYaw, oreX, oreZ));
                addBounded(yawSamples, normalizeYaw(lastYaw));
            }
            cachedOreProfile = null;
            cachedViewProfile = null;
        }

        private synchronized void recordTravel(double distance) {
            travelledBlocks += distance;
            cachedOreProfile = null;
            cachedViewProfile = null;
            cachedPathProfile = null;
        }

        private synchronized void recordPosition(double x, double z, float yaw) {
            if (hasPosition) {
                double dx = x - lastX;
                double dz = z - lastZ;
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance >= MIN_DIRECTION_STEP && distance <= MAX_SINGLE_MOVE_BLOCKS) {
                    double direction = Math.toDegrees(Math.atan2(dz, dx));
                    if (hasDirection) {
                        double delta = Math.abs(normalizeDelta(direction - lastDirectionDeg));
                        if (delta > TURN_ANGLE_DEG) {
                            turns++;
                            straightRun = 0;
                        }
                    }
                    directionSamples++;
                    lastDirectionDeg = direction;
                    hasDirection = true;
                    straightRun += distance;
                    longestStraightRun = Math.max(longestStraightRun, straightRun);
                }
            } else {
                startX = x;
                startZ = z;
                hasPosition = true;
            }
            lastX = x;
            lastZ = z;
            lastYaw = yaw;
            cachedViewProfile = null;
            cachedPathProfile = null;
        }

        private synchronized OreProfile snapshot() {
            if (cachedOreProfile == null) {
                cachedOreProfile = new OreProfile(totalOres, rareOres, travelledBlocks);
            }
            return cachedOreProfile;
        }

        private synchronized ViewCorrelationProfile viewSnapshot() {
            if (cachedViewProfile != null) {
                return cachedViewProfile;
            }
            if (alignments.isEmpty()) {
                cachedViewProfile = ViewCorrelationProfile.empty();
                return cachedViewProfile;
            }
            List<Double> alignmentList = new ArrayList<>(alignments);
            List<Double> yawList = new ArrayList<>(yawSamples);
            double mean = 0.0;
            for (double angle : alignmentList) {
                mean += angle;
            }
            mean /= alignmentList.size();
            cachedViewProfile = new ViewCorrelationProfile(alignmentList.size(), mean,
                    iqr(alignmentList), entropyBits(yawList), straightness());
            return cachedViewProfile;
        }

        private synchronized PathProfile pathSnapshot() {
            if (cachedPathProfile != null) {
                return cachedPathProfile;
            }
            if (directionSamples == 0) {
                cachedPathProfile = PathProfile.empty();
                return cachedPathProfile;
            }
            double turnRate = directionSamples <= 1 ? 0.0 : (double) turns / directionSamples;
            cachedPathProfile = new PathProfile(travelledBlocks, turnRate, longestStraightRun);
            return cachedPathProfile;
        }

        private static void addBounded(ArrayDeque<Double> samples, double value) {
            if (samples.size() >= MAX_VIEW_SAMPLES) {
                samples.removeFirst();
            }
            samples.addLast(value);
        }

        /** 归一化角度差到 [-180,180]。 */
        private static double normalizeDelta(double delta) {
            double value = delta % 360.0;
            if (value >= 180.0) {
                value -= 360.0;
            } else if (value < -180.0) {
                value += 360.0;
            }
            return value;
        }

        /** 路径直线度：起点到当前点的净位移 / 累计路程；没有路程时为 0。 */
        private double straightness() {
            if (!hasPosition || travelledBlocks <= 0) {
                return 0.0;
            }
            double dx = lastX - startX;
            double dz = lastZ - startZ;
            double net = Math.sqrt(dx * dx + dz * dz);
            return Math.min(1.0, net / travelledBlocks);
        }
    }

    /** 玩家视角与「玩家→矿石」方向的夹角（0-180）。 */
    static double alignmentDeg(double px, double pz, float yaw, double oreX, double oreZ) {
        double dx = oreX - px;
        double dz = oreZ - pz;
        if (Math.abs(dx) < 1e-9 && Math.abs(dz) < 1e-9) {
            return 0.0;
        }
        // Bukkit / Minecraft yaw：0 指向 +Z，90 指向 -X
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double delta = (targetYaw - yaw) % 360.0;
        if (delta >= 180.0) {
            delta -= 360.0;
        } else if (delta < -180.0) {
            delta += 360.0;
        }
        return Math.abs(delta);
    }

    /** 四分位距；样本不足 4 个时返回 0。 */
    static double iqr(List<Double> values) {
        if (values.size() < 4) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        double q1 = quantile(sorted, 0.25);
        double q3 = quantile(sorted, 0.75);
        return Math.max(0.0, q3 - q1);
    }

    private static double quantile(List<Double> sorted, double q) {
        double pos = q * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sorted.get(lo);
        }
        double frac = pos - lo;
        return sorted.get(lo) * (1.0 - frac) + sorted.get(hi) * frac;
    }

    /** yaw 分布的香农熵（bit），按 30 度分箱；样本为空返回 0。 */
    static double entropyBits(List<Double> yaws) {
        if (yaws.isEmpty()) {
            return 0.0;
        }
        int buckets = (int) Math.ceil(360.0 / YAW_BUCKET_DEG);
        int[] counts = new int[buckets];
        for (double yaw : yaws) {
            int index = Math.min(buckets - 1, (int) (normalizeYaw((float) yaw) / YAW_BUCKET_DEG));
            counts[index]++;
        }
        double entropy = 0.0;
        for (int count : counts) {
            if (count == 0) {
                continue;
            }
            double p = (double) count / yaws.size();
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    private static double normalizeYaw(float yaw) {
        double y = yaw % 360.0;
        return y < 0 ? y + 360.0 : y;
    }
}
