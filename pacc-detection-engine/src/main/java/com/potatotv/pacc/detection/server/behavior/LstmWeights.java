package com.potatotv.pacc.detection.server.behavior;

import java.util.Objects;
import java.util.Random;

/**
 * LSTM 权重集（技术文档 §7.4：LSTM/GRU 以旋转 / 点击时序为输入分类人类 vs 机器，
 * 目标 95% 置信 ≥ 98% 准确率，对标 arxiv 2512.21377）。
 *
 * <p>矩阵形状：门权重 W*=[hidden][input]、递归权重 U*=[hidden][hidden]、偏置 b*=[hidden]；
 * 输出层 Why=[hidden]（对 h_T 全连接）、bhy 标量。
 *
 * <p>扁平化顺序固定为 wf,uf,bf, wi,ui,bi, wo,uo,bo, wc,uc,bc, why,bhy，
 * {@link #fromFlat(double[], int, int)} 按同一顺序还原，供云端训练权重下发加载。
 */
public record LstmWeights(
        double[][] wf, double[][] uf, double[] bf,
        double[][] wi, double[][] ui, double[] bi,
        double[][] wo, double[][] uo, double[] bo,
        double[][] wc, double[][] uc, double[] bc,
        double[] why, double bhy) {

    public LstmWeights {
        Objects.requireNonNull(wf, "wf");
        Objects.requireNonNull(uf, "uf");
        Objects.requireNonNull(bf, "bf");
        Objects.requireNonNull(wi, "wi");
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(bi, "bi");
        Objects.requireNonNull(wo, "wo");
        Objects.requireNonNull(uo, "uo");
        Objects.requireNonNull(bo, "bo");
        Objects.requireNonNull(wc, "wc");
        Objects.requireNonNull(uc, "uc");
        Objects.requireNonNull(bc, "bc");
        Objects.requireNonNull(why, "why");
        int hidden = bf.length;
        if (uf.length != hidden || wf.length != hidden || wi.length != hidden
                || ui.length != hidden || bi.length != hidden || wo.length != hidden
                || uo.length != hidden || bo.length != hidden || wc.length != hidden
                || uc.length != hidden || bc.length != hidden || why.length != hidden) {
            throw new IllegalArgumentException("权重形状与 hidden 维不匹配");
        }
    }

    /** 矩阵行数（hidden 维）。 */
    public int hiddenSize() {
        return bf.length;
    }

    /** 矩阵列数（input 维，取 wf 的列）。 */
    public int inputSize() {
        return wf[0].length;
    }

    /**
     * 可复现随机初始化（[-0.1, 0.1) 均匀分布）：无训练权重时保证管线可运行。
     * 随机权重无判别力，配合 ConfidenceScorer 的 0.95 ML 门槛几乎不出证据。
     */
    public static LstmWeights random(int inputSize, int hiddenSize, long seed) {
        Random random = new Random(seed);
        return new LstmWeights(
                matrix(random, hiddenSize, inputSize), matrix(random, hiddenSize, hiddenSize), vector(random, hiddenSize),
                matrix(random, hiddenSize, inputSize), matrix(random, hiddenSize, hiddenSize), vector(random, hiddenSize),
                matrix(random, hiddenSize, inputSize), matrix(random, hiddenSize, hiddenSize), vector(random, hiddenSize),
                matrix(random, hiddenSize, inputSize), matrix(random, hiddenSize, hiddenSize), vector(random, hiddenSize),
                vector(random, hiddenSize), (random.nextDouble() - 0.5) * 0.2);
    }

    /** 按固定顺序展开为一维数组（网络传输 / 存储格式）。 */
    public double[] toFlat() {
        int input = inputSize();
        int hidden = hiddenSize();
        int total = 4 * (hidden * input + hidden * hidden + hidden) + hidden + 1;
        double[] flat = new double[total];
        int cursor = 0;
        cursor = write(flat, cursor, wf);
        cursor = write(flat, cursor, uf);
        cursor = write(flat, cursor, bf);
        cursor = write(flat, cursor, wi);
        cursor = write(flat, cursor, ui);
        cursor = write(flat, cursor, bi);
        cursor = write(flat, cursor, wo);
        cursor = write(flat, cursor, uo);
        cursor = write(flat, cursor, bo);
        cursor = write(flat, cursor, wc);
        cursor = write(flat, cursor, uc);
        cursor = write(flat, cursor, bc);
        cursor = write(flat, cursor, why);
        flat[cursor] = bhy;
        return flat;
    }

    /** 从扁平数组按固定顺序还原权重（真实模型加载入口），长度不符即抛错。 */
    public static LstmWeights fromFlat(double[] flat, int inputSize, int hiddenSize) {
        int expected = 4 * (hiddenSize * inputSize + hiddenSize * hiddenSize + hiddenSize) + hiddenSize + 1;
        if (flat == null || flat.length != expected) {
            throw new IllegalArgumentException(
                    "扁平权重长度应为 " + expected + "，实际 " + (flat == null ? -1 : flat.length));
        }
        int[] cursor = {0};
        double[][] wf = readMatrix(flat, cursor, hiddenSize, inputSize);
        double[][] uf = readMatrix(flat, cursor, hiddenSize, hiddenSize);
        double[] bf = readVector(flat, cursor, hiddenSize);
        double[][] wi = readMatrix(flat, cursor, hiddenSize, inputSize);
        double[][] ui = readMatrix(flat, cursor, hiddenSize, hiddenSize);
        double[] bi = readVector(flat, cursor, hiddenSize);
        double[][] wo = readMatrix(flat, cursor, hiddenSize, inputSize);
        double[][] uo = readMatrix(flat, cursor, hiddenSize, hiddenSize);
        double[] bo = readVector(flat, cursor, hiddenSize);
        double[][] wc = readMatrix(flat, cursor, hiddenSize, inputSize);
        double[][] uc = readMatrix(flat, cursor, hiddenSize, hiddenSize);
        double[] bc = readVector(flat, cursor, hiddenSize);
        double[] why = readVector(flat, cursor, hiddenSize);
        double bhy = flat[cursor[0]];
        return new LstmWeights(wf, uf, bf, wi, ui, bi, wo, uo, bo, wc, uc, bc, why, bhy);
    }

    private static double[][] matrix(Random random, int rows, int cols) {
        double[][] result = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                result[r][c] = (random.nextDouble() - 0.5) * 0.2;
            }
        }
        return result;
    }

    private static double[] vector(Random random, int size) {
        double[] result = new double[size];
        for (int i = 0; i < size; i++) {
            result[i] = (random.nextDouble() - 0.5) * 0.2;
        }
        return result;
    }

    private static int write(double[] flat, int cursor, double[][] matrix) {
        for (double[] row : matrix) {
            System.arraycopy(row, 0, flat, cursor, row.length);
            cursor += row.length;
        }
        return cursor;
    }

    private static int write(double[] flat, int cursor, double[] vector) {
        System.arraycopy(vector, 0, flat, cursor, vector.length);
        return cursor + vector.length;
    }

    private static double[][] readMatrix(double[] flat, int[] cursor, int rows, int cols) {
        double[][] result = new double[rows][cols];
        for (int r = 0; r < rows; r++) {
            System.arraycopy(flat, cursor[0], result[r], 0, cols);
            cursor[0] += cols;
        }
        return result;
    }

    private static double[] readVector(double[] flat, int[] cursor, int size) {
        double[] result = new double[size];
        System.arraycopy(flat, cursor[0], result, 0, size);
        cursor[0] += size;
        return result;
    }
}
