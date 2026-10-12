package com.potatotv.pacc.server.core.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * T2 网络模型：延迟 10–350ms 均匀采样 + 抖动 + 包批处理。
 *
 * <p>口径：延迟只影响「投递时序」——每个 tick 包被打上 {@code 逻辑 tick 时间 + 延迟} 的到达时间，
 * 并可能与其他相邻包合并成一批延迟投递。检测侧使用的 tick 时间仍是逻辑 tick 时间
 * （真实服务端靠 transaction 机制把移动包对齐回服务端 tick，见 {@code LatencyCompensator}），
 * 因此延迟不会把合法行为变成偏差超限。
 *
 * <p>全部随机取固定 seed 的 {@link Random}，结果确定可复现。
 */
final class NetworkModel {

    /** 最小延迟（ms）。 */
    static final long MIN_DELAY_MS = 10L;

    /** 最大延迟（ms）。 */
    static final long MAX_DELAY_MS = 350L;

    /** 抖动上限（ms）。 */
    static final long MAX_JITTER_MS = 40L;

    /** 单批最多合并的包数。 */
    static final int MAX_BATCH = 4;

    private final Random rng;

    NetworkModel(long seed) {
        this.rng = new Random(seed);
    }

    /** 均匀采样一次延迟（含抖动）。 */
    long nextDelayMs() {
        long base = MIN_DELAY_MS + (long) (rng.nextDouble() * (MAX_DELAY_MS - MIN_DELAY_MS + 1));
        long jitter = (long) (rng.nextDouble() * (2 * MAX_JITTER_MS + 1)) - MAX_JITTER_MS;
        return Math.max(MIN_DELAY_MS, base + jitter);
    }

    /** 采样一批的合并包数（1..{@value #MAX_BATCH}）。 */
    int nextBatchSize() {
        return 1 + rng.nextInt(MAX_BATCH);
    }

    /**
     * 为 {@code count} 个按序到达的包生成投递计划：返回每批包含的包序号（保持先进先出顺序，
     * 只做合并延迟投递，不重排）。批内序号连续。
     */
    List<List<Integer>> deliveryPlan(int count) {
        List<List<Integer>> batches = new ArrayList<>();
        int index = 0;
        while (index < count) {
            int size = Math.min(nextBatchSize(), count - index);
            List<Integer> batch = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                batch.add(index + i);
            }
            batches.add(batch);
            index += size;
        }
        return batches;
    }
}
