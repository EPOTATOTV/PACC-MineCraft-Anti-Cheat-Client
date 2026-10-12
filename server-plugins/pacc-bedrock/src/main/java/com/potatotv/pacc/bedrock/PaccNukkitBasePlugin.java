package com.potatotv.pacc.bedrock;

import com.potatotv.pacc.bedrock.listener.NukkitCombatListener;
import com.potatotv.pacc.bedrock.listener.NukkitConnectionListener;
import com.potatotv.pacc.bedrock.listener.NukkitInteractListener;
import com.potatotv.pacc.bedrock.listener.NukkitMoveListener;
import com.potatotv.pacc.bedrock.report.PtvHttpReporter;
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
import com.potatotv.pacc.server.core.report.NoopPtvServerReporter;
import com.potatotv.pacc.server.core.report.PtvServerReporter;

import cn.nukkit.Server;
import cn.nukkit.event.Listener;
import cn.nukkit.plugin.PluginBase;

import java.util.ArrayList;
import java.util.List;

/**
 * 基岩版插件入口的抽象基类（Nukkit 与 Nukkit-MOT 共用）。负责装配适配层、检查项、上报通道与事件监听。
 *
 * <p>两个平台模块各自提供一个极薄的入口类继承它，通过 {@link #serverType()} 与
 * {@link #createProtocolAdapter(NukkitConfigLoader)} 声明自己服务的是哪一个服务端。所有 Nukkit API 调用
 * 都在主线程（onEnable / 事件回调）里完成。
 */
public abstract class PaccNukkitBasePlugin extends PluginBase implements Listener {

    /** 本插件服务的服务端类型，用于注册上报与日志。 */
    protected abstract ServerType serverType();

    /** 构造该平台的适配层。基岩版两个平台共用一份实现，仅 ServerType 不同。 */
    protected abstract NukkitPlatformAdapter createProtocolAdapter(NukkitConfigLoader loader);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Server server = Server.getInstance();

        NukkitConfigLoader loader = new NukkitConfigLoader(
                getConfig(),
                serverType(),
                getDescription().getVersion(),
                server.getVersion(),
                server.getName(),
                server.getMaxPlayers(),
                server.getPort());
        ServerPluginConfig pluginConfig = loader.serverPluginConfig();

        NukkitPlatformAdapter adapter = createProtocolAdapter(loader);

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

        DetectionEngine engine = new DetectionEngine(pluginConfig, adapter, checks, reporter);

        server.getPluginManager().registerEvents(new NukkitMoveListener(engine), this);
        server.getPluginManager().registerEvents(new NukkitCombatListener(engine), this);
        server.getPluginManager().registerEvents(new NukkitInteractListener(engine), this);
        server.getPluginManager().registerEvents(new NukkitConnectionListener(engine, adapter), this);

        reporter.register(loader.serverInfo());
        CheatCatalog catalog = IntelChecks.builtinCatalog();
        getLogger().info("PACC 服务端检测已启用（" + serverType()
                + "，处置级别 " + pluginConfig.punishmentMode()
                + "，指纹库 " + catalog.total() + " 条，覆盖全类别=" + catalog.coversAllCategories() + "）");
    }

    @Override
    public void onDisable() {
        getLogger().info("PACC 服务端检测已卸载");
    }
}
