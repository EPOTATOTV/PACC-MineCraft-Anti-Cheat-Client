package com.potatotv.pacc.geyser;

import com.potatotv.pacc.geyser.bridge.ProxyDetectionBridge;
import com.potatotv.pacc.geyser.config.GeyserConfigLoader;
import com.potatotv.pacc.geyser.config.SimpleYaml;
import com.potatotv.pacc.geyser.packet.PacketHandlerRegistrar;
import com.potatotv.pacc.geyser.report.PtvHttpReporter;
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
import com.potatotv.pacc.server.core.report.NoopPtvServerReporter;
import com.potatotv.pacc.server.core.report.PtvServerReporter;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.network.BedrockListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PACC 的 Geyser 扩展入口。
 *
 * <p>说明：需求描述里写的是 {@code org.geysermc.geyser.extension.GeyserExtension#init()}，但核对 Geyser 公开 API
 * （2.11.x）后，扩展入口实际是「主类实现 {@link Extension} 接口 + 用 {@code @Subscribe} 订阅生命周期事件」，
 * 没有 {@code GeyserExtension.init()} 这种写法，故按真实 API 落地。
 *
 * <p>职责：加载配置、装配适配层 / 检查项 / 上报通道、按连接设备类型调整阈值基线、管理在线设备的阈值档位。
 * 检测结论经 PACC 独立通道上报 PTV；Geyser 侧只做本连接范围内的告警与断开，不做跨服封禁。
 */
public final class PaccGeyserExtension implements Extension {

    private volatile GeyserConfigLoader loader;
    private volatile GeyserPlatformAdapter adapter;
    private volatile PtvServerReporter reporter;
    private volatile DetectionEngine engine;
    private volatile boolean deviceAdjust;
    private volatile ServerPluginConfig baseConfig;
    private volatile byte[] evidenceChainKey;
    private volatile DeviceThresholds.DeviceClass effectiveClass = DeviceThresholds.DeviceClass.UNKNOWN;

    private final Map<String, DeviceThresholds.DeviceClass> playerDevices = new ConcurrentHashMap<>();

    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent event) {
        try {
            this.loader = loadConfig();
        } catch (RuntimeException | IOException ex) {
            logger().severe("PACC 配置加载失败，扩展将不启用检测", ex);
        }
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        GeyserConfigLoader config = this.loader;
        if (config == null) {
            return;
        }
        this.baseConfig = config.serverPluginConfig();
        this.deviceAdjust = config.deviceThresholdAdjust();
        this.adapter = new GeyserPlatformAdapter(geyserApi(), config, this::logWarning);
        this.reporter = config.ptvUrl().isBlank()
                ? NoopPtvServerReporter.INSTANCE
                : new PtvHttpReporter(config.ptvUrl(), config.ptvSecret());
        this.effectiveClass = DeviceThresholds.DeviceClass.UNKNOWN;
        // 证据链密钥（§8.2）从 PTV 上报密钥派生，过短自动降级为不启用
        this.evidenceChainKey = IntelChecks.chainKeyFrom(config.ptvSecret());
        this.engine = createEngine(config.serverPluginConfig());

        PacketHandlerRegistrar packetRegistrar = new PacketHandlerRegistrar.Unsupported(logger()::warning);
        packetRegistrar.registerBedrockPacketHandler(packet -> { });
        packetRegistrar.registerJavaPacketHandler(packet -> { });
        new ProxyDetectionBridge.Noop(logger()::info).onConfirmation(confirmation -> { });

        reporter.register(config.serverInfo());
        CheatCatalog catalog = IntelChecks.builtinCatalog();
        logger().info("PACC 服务端检测已启用（GEYSER，处置级别 " + config.serverPluginConfig().punishmentMode()
                + "，指纹库 " + catalog.total() + " 条，覆盖全类别=" + catalog.coversAllCategories() + "）");
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        DetectionEngine current = this.engine;
        if (current == null) {
            return;
        }
        GeyserConnection connection = event.connection();
        String playerId = connection.javaUuid().toString();
        DeviceThresholds.DeviceClass deviceClass = adapter.deviceClass(playerId);
        playerDevices.put(playerId, deviceClass);
        rebuildEngineIfNeeded();
        // Geyser 侧拿不到坐标 / 战斗数据，此处只利用连接信息跑一次客户端标识检查。
        current.handleClientBrand(playerId, connection.version());
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        DetectionEngine current = this.engine;
        if (current == null) {
            return;
        }
        String playerId = event.connection().javaUuid().toString();
        playerDevices.remove(playerId);
        current.resetPlayer(playerId);
        rebuildEngineIfNeeded();
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        logger().info("PACC 服务端检测已卸载");
    }

    private DetectionEngine createEngine(ServerPluginConfig config) {
        List<ServerCheck> checks = new ArrayList<>(List.of(
                new MoveCheck(config),
                new CombatCheck(config),
                new PacketCheck(),
                new InventoryCheck(),
                new InteractionCheck(config),
                new ClientBrandCheck(config),
                new AddonCheck(config),
                new DataConsistencyCheck()));
        // 引擎升级（技术文档 §4.2）：追加物理预测 / 行为 / 战斗 / 数据包四类 intel 检查
        checks.addAll(IntelChecks.defaults(evidenceChainKey));
        return new DetectionEngine(config, adapter, checks, reporter);
    }

    /** 在线设备里出现更宽松的档位时才重建引擎，避免每次进出服都重建。 */
    private void rebuildEngineIfNeeded() {
        if (!deviceAdjust || baseConfig == null) {
            return;
        }
        DeviceThresholds.DeviceClass lenient = DeviceThresholds.mostLenient(playerDevices.values());
        if (lenient == effectiveClass) {
            return;
        }
        effectiveClass = lenient;
        this.engine = createEngine(DeviceThresholds.adjust(baseConfig, lenient));
    }

    private GeyserConfigLoader loadConfig() throws IOException {
        Path folder = dataFolder();
        Files.createDirectories(folder);
        Path configFile = folder.resolve("config.yml");
        if (Files.notExists(configFile)) {
            copyDefaultConfig(configFile);
        }
        Map<String, Object> parsed = SimpleYaml.parse(Files.readString(configFile, StandardCharsets.UTF_8));

        GeyserApi api = geyserApi();
        BedrockListener listener = api.bedrockListener();
        String serverName = listener == null || listener.serverName() == null ? "Geyser" : listener.serverName();
        int port = listener == null ? 0 : listener.port();
        String serverVersion = api.supportedJavaVersion() == null
                ? "unknown"
                : api.supportedJavaVersion().versionString();
        String pluginVersion = description() == null ? "6.0.0" : description().version();
        return new GeyserConfigLoader(parsed, pluginVersion, serverVersion, serverName, 0, port);
    }

    private void copyDefaultConfig(Path target) throws IOException {
        try (InputStream in = PaccGeyserExtension.class.getResourceAsStream("/config.yml")) {
            if (in != null) {
                Files.copy(in, target);
            }
        }
    }

    private void logWarning(String message) {
        logger().warning(message);
    }
}
