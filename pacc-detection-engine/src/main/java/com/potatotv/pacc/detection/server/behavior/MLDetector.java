package com.potatotv.pacc.detection.server.behavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.potatotv.pacc.detection.fusion.ConfidenceScorer;
import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.profile.RotationSample;

/**
 * ML 时序检测器（技术文档 §7.4）：纯 Java LSTM 前向推理，输入旋转 / 点击特征序列，
 * 输出机器概率。
 *
 * <p>无训练权重时可用 {@link LstmWeights#random(int, int, long)} 保证管线可运行——
 * 随机权重无判别力，其输出会被 {@link ConfidenceScorer#mlGate(double)} 的 0.95 门槛
 * （文档 §8.1：ML 结论达 95% 置信才采信）拦下，不会误出证据；
 * 生产环境由云端训练权重经 {@link LstmWeights#fromFlat(double[], int, int)} 下发加载。
 */
public final class MLDetector {

    public static final String DETECTOR_ID = "behavior-ml";

    private final LstmWeights weights;

    public MLDetector(int inputSize, int hiddenSize, LstmWeights weights) {
        if (weights == null) {
            throw new IllegalArgumentException("weights 不能为空");
        }
        if (weights.inputSize() != inputSize || weights.hiddenSize() != hiddenSize) {
            throw new IllegalArgumentException(
                    "权重维度与声明不符: 权重=" + weights.inputSize() + "x" + weights.hiddenSize()
                            + " 声明=" + inputSize + "x" + hiddenSize);
        }
        this.weights = weights;
    }

    /**
     * 标准 LSTM 前向：对序列每步计算
     * f=σ(Wf·x+Uf·h+bf)、i=σ(Wi·x+Ui·h+bi)、o=σ(Wo·x+Uo·h+bo)、c̃=tanh(Wc·x+Uc·h+bc)、
     * c=f⊙c_prev+i⊙c̃、h=o⊙tanh(c)；最终 h_T 经全连接 + sigmoid 输出机器概率。
     *
     * @return 长度 1 的数组，唯一元素为机器概率 ∈ (0,1)
     */
    public double[] forward(List<double[]> sequence) {
        if (sequence == null || sequence.isEmpty()) {
            return new double[] {0.5};
        }
        int hidden = weights.hiddenSize();
        double[] h = new double[hidden];
        double[] c = new double[hidden];
        for (double[] x : sequence) {
            double[] nextH = new double[hidden];
            double[] nextC = new double[hidden];
            for (int j = 0; j < hidden; j++) {
                double f = sigmoid(dot(weights.wf()[j], x) + dot(weights.uf()[j], h) + weights.bf()[j]);
                double iGate = sigmoid(dot(weights.wi()[j], x) + dot(weights.ui()[j], h) + weights.bi()[j]);
                double oGate = sigmoid(dot(weights.wo()[j], x) + dot(weights.uo()[j], h) + weights.bo()[j]);
                double candidate = Math.tanh(dot(weights.wc()[j], x) + dot(weights.uc()[j], h) + weights.bc()[j]);
                nextC[j] = f * c[j] + iGate * candidate;
                nextH[j] = oGate * Math.tanh(nextC[j]);
            }
            h = nextH;
            c = nextC;
        }
        double z = dot(weights.why(), h) + weights.bhy();
        return new double[] {sigmoid(z)};
    }

    /**
     * 分类：机器概率达到 ML 门槛（0.95）才出证据。
     */
    public Optional<Evidence> classify(String playerId, List<double[]> featureSequence) {
        double p = forward(featureSequence)[0];
        if (p < ConfidenceScorer.DEFAULT_ML_GATE) {
            return Optional.empty();
        }
        String detail = String.format(Locale.ROOT, "lstm p=%.3f 玩家=%s 序列=%d",
                p, playerId, featureSequence == null ? 0 : featureSequence.size());
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, p, detail));
    }

    /**
     * 点击间隔特征（3 维）：归一化间隔（秒）、相邻差分（秒）、平方差分。
     */
    public static List<double[]> clickFeatures(List<Long> intervals) {
        List<double[]> features = new ArrayList<>(intervals == null ? 0 : intervals.size());
        if (intervals == null) {
            return features;
        }
        for (int i = 0; i < intervals.size(); i++) {
            double current = intervals.get(i) / 1000.0;
            double previous = i > 0 ? intervals.get(i - 1) / 1000.0 : current;
            double diff = current - previous;
            features.add(new double[] {current, diff, diff * diff});
        }
        return features;
    }

    /**
     * 旋转特征（3 维）：yaw 角速度 /100、pitch 角速度 /100、归一化 yaw 差 /180；
     * 首样本无前驱，特征记 0。
     */
    public static List<double[]> rotationFeatures(List<RotationSample> rotations) {
        List<double[]> features = new ArrayList<>(rotations == null ? 0 : rotations.size());
        if (rotations == null || rotations.isEmpty()) {
            return features;
        }
        features.add(new double[] {0.0, 0.0, 0.0});
        for (int i = 1; i < rotations.size(); i++) {
            RotationSample a = rotations.get(i - 1);
            RotationSample b = rotations.get(i);
            features.add(new double[] {
                    RotationSample.yawRate(a, b) / 100.0,
                    RotationSample.pitchRate(a, b) / 100.0,
                    RotationSample.normalizeDelta(b.yawDeg() - a.yawDeg()) / 180.0});
        }
        return features;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /** 数值稳定的 sigmoid，输出严格落在 (0,1)。 */
    private static double sigmoid(double z) {
        if (z >= 0) {
            return 1.0 / (1.0 + Math.exp(-z));
        }
        double e = Math.exp(z);
        return e / (1.0 + e);
    }
}
