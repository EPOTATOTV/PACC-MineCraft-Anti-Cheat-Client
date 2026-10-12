package com.potatotv.pacc.detection.server.prediction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事务（transaction）延迟补偿器（技术文档 §7.2）。
 *
 * <p>Grim 口径：服务端周期性向客户端发出带自增 id 的事务包，客户端处理完毕后原样回 ack。
 * 服务端在 ack 回来时才能确认「ack 之前收到的移动包属于哪个时间切片」，据此把移动包
 * 对齐到正确的服务端 tick，消除网络抖动造成的预测误判；ack 率与确认延迟本身也是
 * Disabler / PingSpoof 的检测信号（文档 §九，对接 {@code packet.TransactionSync}）。
 *
 * <p>延迟口径：latency = clientTimeMs - serverTimeMs，要求调用方两侧使用同一时钟基准
 * （如同一台物理机或 NTP 对齐）；跨机时钟漂移由调用方在上层归一化。
 *
 * <p>线程安全：发事务在 Netty 线程、ack 回包在 IO 线程、查询在检测线程，全部并发安全。
 */
public final class LatencyCompensator {

    /** 悬挂事务软上限：超过视为客户端整体失联，全部按丢弃处理，防 map 无界增长。 */
    private static final int MAX_PENDING = 10_000;

    /** 参与延迟中位数估计的最近 ack 样本数。 */
    private static final int LATENCY_WINDOW = 20;

    private final AtomicInteger nextId = new AtomicInteger();
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger acked = new AtomicInteger();

    /** transactionId → 发出时刻（服务端时钟 ms）。 */
    private final ConcurrentHashMap<Integer, Long> pending = new ConcurrentHashMap<>();

    /** 最近若干次 ack 的延迟样本（ms），synchronized 守护。 */
    private final Deque<Long> recentLatencies = new ArrayDeque<>(LATENCY_WINDOW);

    /**
     * 发出一个事务，返回自增 id。悬挂事务超过 {@value #MAX_PENDING} 个时整体清空
     * （视为全部丢失，旧 id 的迟到 ack 会被忽略）。
     */
    public int sendTransaction() {
        int id = nextId.incrementAndGet();
        if (pending.size() >= MAX_PENDING) {
            pending.clear();
        }
        pending.put(id, System.currentTimeMillis());
        sent.incrementAndGet();
        return id;
    }

    /** 客户端确认一个事务；未知 / 已确认的 id 直接忽略。 */
    public void ack(int transactionId, long clientTimeMs) {
        Long serverTimeMs = pending.remove(transactionId);
        if (serverTimeMs == null) {
            return;
        }
        acked.incrementAndGet();
        long latency = clientTimeMs - serverTimeMs;
        if (latency >= 0) {
            synchronized (recentLatencies) {
                if (recentLatencies.size() >= LATENCY_WINDOW) {
                    recentLatencies.pollFirst();
                }
                recentLatencies.addLast(latency);
            }
        }
    }

    /**
     * 最近 {@value #LATENCY_WINDOW} 个 ack 延迟的中位数（ms）；无样本返回 -1。
     */
    public long estimatedLatencyMs() {
        List<Long> snapshot;
        synchronized (recentLatencies) {
            if (recentLatencies.isEmpty()) {
                return -1;
            }
            snapshot = new ArrayList<>(recentLatencies);
        }
        snapshot.sort(Long::compare);
        int n = snapshot.size();
        long median = (n % 2 == 1)
                ? snapshot.get(n / 2)
                : (snapshot.get(n / 2 - 1) + snapshot.get(n / 2)) / 2;
        return Math.max(0, median);
    }

    /** 事务是否仍未确认。 */
    public boolean isPending(int transactionId) {
        return pending.containsKey(transactionId);
    }

    /** 累计发出的事务数。 */
    public int sentCount() {
        return sent.get();
    }

    /** 累计确认的事务数。 */
    public int ackedCount() {
        return acked.get();
    }
}
