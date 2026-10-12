package com.potatotv.pacc.bukkit;

import com.potatotv.pacc.bukkit.listener.CombatListener;
import com.potatotv.pacc.bukkit.listener.ConnectionListener;
import com.potatotv.pacc.bukkit.listener.MoveListener;
import com.potatotv.pacc.bukkit.report.PtvHttpReporter;
import com.potatotv.pacc.server.core.check.AddonCheck;
import com.potatotv.pacc.server.core.check.ClientBrandCheck;
import com.potatotv.pacc.server.core.check.CombatCheck;
import com.potatotv.pacc.server.core.check.DataConsistencyCheck;
import com.potatotv.pacc.server.core.check.InteractionCheck;
import com.potatotv.pacc.server.core.check.InventoryCheck;
import com.potatotv.pacc.server.core.check.MoveCheck;
import com.potatotv.pacc.server.core.check.PacketCheck;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.detection.threatintel.CheatCatalog;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.intel.IntelChecks;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.model.ServerType;
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

    private DetectionEngine engine;
    private ClientBrandTracker brandTracker;
    private ModHashTracker modHashTracker;

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

        List<ServerCheck> checks = new ArrayList<>(List.of(
                new MoveCheck(pluginConfig),
                new CombatCheck(pluginConfig),
                new PacketCheck(),
                new InventoryCheck(),
                new InteractionCheck(pluginConfig),
                new ClientBrandCheck(pluginConfig),
                new AddonCheck(pluginConfig),
                new DataConsistencyCheck()));
        // 引擎升级（技术文档 §4.2）：追加物理预测 / 行为 / 战斗 / 数据包四类 intel 检查；
        // 以 PTV 上报密钥派生证据链密钥（§8.2），过短自动降级为不启用链式哈希
        checks.addAll(IntelChecks.defaults(IntelChecks.chainKeyFrom(loader.ptvSecret())));

        PtvServerReporter reporter = loader.ptvUrl().isBlank()
                ? NoopPtvServerReporter.INSTANCE
                : new PtvHttpReporter(loader.ptvUrl(), loader.ptvSecret());

        engine = new DetectionEngine(pluginConfig, adapter, checks, reporter);

        Bukkit.getPluginManager().registerEvents(new MoveListener(engine), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(engine), this);
        Bukkit.getPluginManager().registerEvents(new ConnectionListener(engine, brandTracker, modHashTracker), this);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, BRAND_CHANNEL, brandTracker);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, REGISTER_CHANNEL, brandTracker);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, MOD_CHANNEL, modHashTracker);

        reporter.register(loader.serverInfo());
        CheatCatalog catalog = IntelChecks.builtinCatalog();
        getLogger().info("PACC 服务端检测已启用（" + serverType + "，处置级别 " + pluginConfig.punishmentMode()
                + "，指纹库 " + catalog.total() + " 条，覆盖全类别=" + catalog.coversAllCategories() + "）");
    }

    @Override
    public void onDisable() {
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
