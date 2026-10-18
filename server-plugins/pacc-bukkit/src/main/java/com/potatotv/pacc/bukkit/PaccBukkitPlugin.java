package com.potatotv.pacc.bukkit;

import com.potatotv.pacc.bukkit.listener.BlockBreakListener;
import com.potatotv.pacc.bukkit.listener.BlockPlaceListener;
import com.potatotv.pacc.bukkit.listener.CombatListener;
import com.potatotv.pacc.bukkit.listener.ConnectionListener;
import com.potatotv.pacc.bukkit.listener.MoveListener;
import com.potatotv.pacc.bukkit.packet.PacketEventsBridge;
import com.potatotv.pacc.bukkit.report.PtvHttpReporter;
import com.potatotv.pacc.server.core.check.AddonCheck;
import com.potatotv.pacc.server.core.check.ClientBrandCheck;
import com.potatotv.pacc.server.core.check.CombatCheck;
import com.potatotv.pacc.server.core.check.DataConsistencyCheck;
import com.potatotv.pacc.server.core.check.InteractionCheck;
import com.potatotv.pacc.server.core.check.InventoryCheck;
import com.potatotv.pacc.server.core.check.MoveCheck;
import com.potatotv.pacc.server.core.check.PacketCheck;
import com.potatotv.pacc.server.core.check.PhaseCheck;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.check.XRayCheck;
import com.potatotv.pacc.detection.threatintel.CheatCatalog;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.intel.IntelChecks;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.model.ServerType;
import com.potatotv.pacc.server.core.packet.PacketCapabilityMatrix;
import com.potatotv.pacc.server.core.protocol.ModHashParser;
import com.potatotv.pacc.server.core.report.NoopPtvServerReporter;
import com.potatotv.pacc.server.core.report.PtvServerReporter;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * PACC 的 Bukkit 插件入口（Paper 与 Spigot 共用）。负责装配适配层、检查项、上报通道与事件监听；
 * 运行在 Paper 还是 Spigot 由 {@link BukkitPlatformAdapter#detectServerType()} 在启动时判定。
 * 所有 Bukkit API 调用都在主线程（onEnable / 事件回调）里完成。
 *
 * <p>本类不注册为插件主类：Paper / Spigot 产物各自用自己的主类继承它（见 pacc-paper 的
 * {@code PaccPaperPlugin} 与 pacc-spigot 的 {@code PaccSpigotPlugin}），这样每个下载件的 plugin.yml
 * 都指向本产物内的真实主类，而不是靠运行时平台判定隐式复用。
 */
public class PaccBukkitPlugin extends JavaPlugin {

    private static final String BRAND_CHANNEL = "minecraft:brand";
    private static final String REGISTER_CHANNEL = "minecraft:register";
    private static final String MOD_CHANNEL = ModHashParser.CHANNEL;
    /** PacketEvents 的插件名，plugin.yml 的 softdepend 也用它。 */
    private static final String PACKET_EVENTS_PLUGIN = "PacketEvents";

    private DetectionEngine engine;
    private ClientBrandTracker brandTracker;
    private ModHashTracker modHashTracker;
    private PacketEventsBridge packetBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration cfg = getConfig();

        ServerType serverType = BukkitPlatformAdapter.detectServerType();
        PluginConfigLoader loader = new PluginConfigLoader(
                cfg,
                serverType,
                getDescription().getVersion(),
                Bukkit.getVersion(),
                Bukkit.getServer().getName(),
                Bukkit.getMaxPlayers(),
                Bukkit.getPort());
        ServerPluginConfig pluginConfig = loader.serverPluginConfig();

        brandTracker = new ClientBrandTracker();
        modHashTracker = new ModHashTracker();
        BukkitPlatformAdapter adapter = new BukkitPlatformAdapter(this, loader, brandTracker, modHashTracker);

        // 先接数据包源再装配检查项：事务发包口要在构造时注入，否则延迟补偿拿不到真实 id
        packetBridge = installPacketSource(adapter);

        List<ServerCheck> checks = new ArrayList<>(List.of(
                new MoveCheck(pluginConfig),
                new CombatCheck(pluginConfig),
                new PacketCheck(),
                new PhaseCheck(adapter.blockProbe()),
                new XRayCheck(),
                new InventoryCheck(),
                new InteractionCheck(pluginConfig),
                new ClientBrandCheck(pluginConfig),
                new AddonCheck(pluginConfig),
                new DataConsistencyCheck()));
        // 引擎升级（技术文档 §4.2）：追加物理预测 / 行为 / 战斗 / 数据包四类 intel 检查；
        // 以 PTV 上报密钥派生证据链密钥（§8.2），过短自动降级为不启用链式哈希
        checks.addAll(IntelChecks.defaults(IntelChecks.chainKeyFrom(loader.ptvSecret()),
                adapter.transactionSender()));

        PtvServerReporter reporter = loader.ptvUrl().isBlank()
                ? NoopPtvServerReporter.INSTANCE
                : new PtvHttpReporter(loader.ptvUrl(), loader.ptvSecret());

        engine = new DetectionEngine(pluginConfig, adapter, checks, reporter);
        // 每刻喂一次实测刻长（§5.3 U-05）：Timer 的归一化基准随 TPS 漂移，卡服时不会全服误报
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            DetectionEngine current = engine;
            if (current != null) {
                current.handleTick(System.currentTimeMillis());
            }
        }, 1L, 1L);

        Bukkit.getPluginManager().registerEvents(new MoveListener(engine), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(engine), this);
        Bukkit.getPluginManager().registerEvents(new BlockBreakListener(engine), this);
        Bukkit.getPluginManager().registerEvents(new BlockPlaceListener(engine), this);
        Bukkit.getPluginManager().registerEvents(new ConnectionListener(engine, brandTracker, modHashTracker), this);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, BRAND_CHANNEL, brandTracker);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, REGISTER_CHANNEL, brandTracker);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, MOD_CHANNEL, modHashTracker);

        reporter.register(loader.serverInfo());
        CheatCatalog catalog = IntelChecks.builtinCatalog();
        getLogger().info("PACC 服务端检测已启用（" + serverType + "，处置级别 " + pluginConfig.punishmentMode()
                + "，指纹库 " + catalog.total() + " 条，覆盖全类别=" + catalog.coversAllCategories() + "）");
        getLogger().info(PacketCapabilityMatrix.report(adapter.packetCapability()));
    }

    /**
     * 接入数据包源。只有服务端确实装了 PacketEvents 时才碰 {@link PacketEventsBridge}——
     * 那个类引用了 PacketEvents 的类型，没装插件时加载它会直接抛 NoClassDefFoundError。
     *
     * @return 已接入的桥；未接入返回 {@code null}
     */
    private PacketEventsBridge installPacketSource(BukkitPlatformAdapter adapter) {
        if (Bukkit.getPluginManager().getPlugin(PACKET_EVENTS_PLUGIN) == null) {
            getLogger().info("未检测到 PacketEvents，包级检测降级为事件口径（Timer/Blink/Phase 不可用）");
            return null;
        }
        try {
            PacketEventsBridge bridge = PacketEventsBridge.install(this, view -> {
                DetectionEngine current = engine;
                if (current != null) {
                    current.handlePacket(view);
                }
            });
            if (bridge != null) {
                adapter.setTransactionSender(bridge.transactionSender(BukkitPlatformAdapter::resolvePlayer));
            }
            return bridge;
        } catch (LinkageError | RuntimeException ex) {
            // PacketEvents 版本不兼容时不能把整个插件带崩：退回事件口径并说明原因
            getLogger().warning("接入 PacketEvents 失败，包级检测降级为事件口径：" + ex);
            return null;
        }
    }

    @Override
    public void onDisable() {
        if (packetBridge != null) {
            packetBridge.uninstall();
        }
        if (brandTracker != null) {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(this, BRAND_CHANNEL, brandTracker);
            Bukkit.getMessenger().unregisterIncomingPluginChannel(this, REGISTER_CHANNEL, brandTracker);
        }
        if (modHashTracker != null) {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(this, MOD_CHANNEL, modHashTracker);
        }
        getLogger().info("PACC 服务端检测已卸载");
    }
}
