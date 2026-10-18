package com.potatotv.pacc.bukkit.packet;

import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.packet.TransactionSender;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * PacketEvents 接入（技术文档 §5.1 U-01 / F-08）。
 *
 * <p>选 PacketEvents 而不是 ProtocolLib：PacketEvents 自带协议版本适配，不需要跟着服务端版本
 * 换 ProtocolLib 版本，运维少一个踩坑点。
 *
 * <p>依赖形态是 {@code provided} + {@code softdepend}，不 shade 进插件 jar。理由有两条：一是
 * PacketEvents 的 spigot 产物自带 {@code plugin.yml}，shade 进来会和本插件的 plugin.yml 撞；
 * 二是它体量大（含 Netty 适配层），塞进每个服务端插件产物里不合适。服务端没装 PacketEvents 时
 * 本类根本不会被加载，检测退回事件口径，能力差异由
 * {@code PacketCapabilityMatrix} 声明——不会静默失效。
 *
 * <p>本类**只做**接入与卸载，不做协议初始化：服务端上的 PacketEvents 插件自己会完成
 * {@code setAPI / load / init}，插件重复初始化会把它的状态搅乱。
 */
public final class PacketEventsBridge {

    private final PacketPump pump;
    private final PacketStreamListener listener;
    private final PacketListenerCommon handle;
    private final Plugin plugin;

    private PacketEventsBridge(Plugin plugin, PacketPump pump,
                              PacketStreamListener listener, PacketListenerCommon handle) {
        this.plugin = plugin;
        this.pump = pump;
        this.listener = listener;
        this.handle = handle;
    }

    /**
     * 注册数据包监听。调用前必须确认服务端确实装了 PacketEvents，否则本类加载即抛
     * {@code NoClassDefFoundError}。
     *
     * @param sink 主线程上的包消费口，通常是 {@code engine::handlePacket}
     * @return 已接入的桥；PacketEvents 未初始化时返回 {@code null}
     */
    public static PacketEventsBridge install(Plugin plugin, Consumer<PacketView> sink) {
        if (PacketEvents.getAPI() == null || !PacketEvents.getAPI().isInitialized()) {
            plugin.getLogger().warning("PacketEvents 插件存在但尚未初始化，包级检测本次不可用");
            return null;
        }
        PacketPump pump = new PacketPump(plugin, sink);
        PacketStreamListener listener = new PacketStreamListener(pump);
        // MONITOR：只读不取消，让它排在其它插件的监听之后，不改变任何人的行为
        PacketListenerCommon handle = PacketEvents.getAPI().getEventManager()
                .registerListener(listener, PacketListenerPriority.MONITOR);
        plugin.getLogger().info("已接入 PacketEvents " + PacketEvents.getAPI().getVersion()
                + "，数据包在 Netty 线程解析后转主线程检测");
        return new PacketEventsBridge(plugin, pump, listener, handle);
    }

    /** 卸载监听并汇报丢弃与解析失败计数——这两个数字是排查「为什么这个包没被检测到」的入口。 */
    public void uninstall() {
        PacketEvents.getAPI().getEventManager().unregisterListener(handle);
        long dropped = pump.droppedCount();
        long failures = listener.parseFailures();
        if (dropped > 0 || failures > 0) {
            plugin.getLogger().warning("数据包桥已卸载：丢弃 " + dropped + " 包（队列满），"
                    + "解析失败 " + failures + " 包（畸形包）");
        }
    }

    /**
     * 事务发包口：向客户端发一个 Ping 包并把它登记的 id 交给延迟补偿。
     *
     * <p>为什么必须真发：延迟补偿与 Disabler 判定都是「发出 id → 客户端按 id 回 Pong」的配对。
     * 检测侧凭空造 id 不会有任何 Pong 回来，ack 率恒为 0，会把全服玩家判成 Disabler。
     * {@code PacketType.Play.Server.PING} 的 id 由客户端原样回在 Pong 里，正是配对用的那条线。
     *
     * @param playerResolver 按玩家 id 取平台玩家对象，取不到返回 {@code null}
     */
    public TransactionSender transactionSender(Function<String, Object> playerResolver) {
        return new PacketEventsTransactionSender(playerResolver);
    }

    /** 具体发包实现。静态内嵌是为了让「谁引用了 PacketEvents 类型」一眼可见。 */
    private static final class PacketEventsTransactionSender implements TransactionSender {

        private final Function<String, Object> playerResolver;
        private final AtomicInteger ids = new AtomicInteger();

        private PacketEventsTransactionSender(Function<String, Object> playerResolver) {
            this.playerResolver = playerResolver;
        }

        @Override
        public int send(String playerId) {
            Object player = playerResolver.apply(playerId);
            if (player == null) {
                return -1;
            }
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null) {
                return -1;
            }
            int id = ids.incrementAndGet();
            user.sendPacket(new WrapperPlayServerPing(id));
            return id;
        }
    }
}
