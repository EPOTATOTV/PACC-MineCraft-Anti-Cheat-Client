package com.potatotv.pacc.bukkit.packet;

import com.potatotv.pacc.server.core.model.PacketView;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 数据包泵：把 Netty 线程解析出的 {@link PacketView} 转交到主线程消费。
 *
 * <p>为什么必须转主线程：检测链路的末端会调 {@code adapter.alert / punish}，以及读玩家位置、
 * 世界方块，这些都是 Bukkit API，只在主线程合法。Netty 线程直接调会在 Paper 上抛线程检查异常。
 *
 * <p>为什么要限容：包到达速率由客户端说了算，作弊客户端可以在 1 秒内灌入上万包。任务队列无限增长
 * 会把主线程拖垮，所以这里用有界队列，装不下就丢弃并计数，丢弃数在卸载时打进日志——宁可少看几包，
 * 也不能让检测本身成为压垮服务器的原因。
 */
final class PacketPump {

    /** 队列容量：100 人 × 20 包/秒 ≈ 2000 包/秒，留一倍余量。 */
    static final int QUEUE_CAPACITY = 4096;

    /** 单次主线程任务最多消费的包数，避免一次 drain 占满一整刻。 */
    static final int DRAIN_BATCH = 256;

    private final Plugin plugin;
    private final Consumer<PacketView> sink;
    private final BlockingQueue<PacketView> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();

    PacketPump(Plugin plugin, Consumer<PacketView> sink) {
        this.plugin = plugin;
        this.sink = sink;
    }

    /** 由 Netty 线程调用。队列满时丢弃并计数，绝不阻塞网络线程。 */
    void offer(PacketView packet) {
        if (queue.offer(packet)) {
            scheduleDrain();
            return;
        }
        dropped.incrementAndGet();
    }

    /** 因队列满被丢弃的包数。 */
    long droppedCount() {
        return dropped.get();
    }

    private void scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTask(plugin, this::drain);
        }
    }

    private void drain() {
        PacketView packet;
        int consumed = 0;
        while (consumed < DRAIN_BATCH && (packet = queue.poll()) != null) {
            sink.accept(packet);
            consumed++;
        }
        drainScheduled.set(false);
        // 复位与 drain 之间可能有新包入队，这里再确认一次，避免残包等到下次入队才被处理。
        if (!queue.isEmpty()) {
            scheduleDrain();
        }
    }
}
