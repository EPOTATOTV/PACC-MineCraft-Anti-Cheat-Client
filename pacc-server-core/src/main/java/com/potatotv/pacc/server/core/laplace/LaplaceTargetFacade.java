package com.potatotv.pacc.server.core.laplace;

import com.potatotv.pacc.server.core.ServerPlatformAdapter;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.report.NoopPtvServerReporter;
import com.potatotv.pacc.server.core.report.PtvServerReporter;

import java.util.List;
import java.util.Map;

/**
 * 拉普拉斯精灵的被测目标门面：平台侧适配器唯一需要对接的类。
 *
 * <p>拉普拉斯是独立仓库（github.com/EPOTATOTV/LaplaceSprite），它的进程内轨要求实现
 * {@code io.laplace.sprite.adapters.sim.DetectionEngine}：
 *
 * <pre>{@code
 * public final class PaccDetectionEngine implements DetectionEngine {
 *     private final LaplaceTargetFacade target;
 *     public List<FlagEvent> evaluate(DetectionContext ctx) {
 *         // 1. 把 ctx 的事件流按类型映射到下面这些 onXxx 方法
 *         // 2. 把返回的 LaplaceFlag 转成平台自己的 FlagEvent
 *     }
 * }
 * }</pre>
 *
 * <p>这么做的好处是平台侧不必知道 {@code DetectionEngine} 的构造、检查项装配、配置模型与
 * 上报边界；它只知道「有哪些事件入口、每个入口返回什么」——这正是拉普拉斯文档 4.4 要求的
 * 唯一契约。PACC 这边也不引入对拉普拉斯任何类型的依赖，两个项目保持零代码耦合。
 *
 * <p>默认只告警：门面不改变处置语义，{@code punish} 仍由 {@link ServerPluginConfig} 的
 * {@code punishmentMode} 控制。在平台测试服里一般保持 0。
 */
public final class LaplaceTargetFacade {

    private final DetectionEngine engine;
    private final ServerPluginConfig config;

    /** 平台侧自己装一套检查项时用这个构造口。 */
    public LaplaceTargetFacade(ServerPluginConfig config, ServerPlatformAdapter adapter,
                              List<ServerCheck> checks, PtvServerReporter reporter) {
        this.config = config == null ? ServerPluginConfig.defaults() : config;
        PtvServerReporter effectiveReporter = reporter == null ? NoopPtvServerReporter.INSTANCE : reporter;
        this.engine = new DetectionEngine(this.config, adapter, checks, effectiveReporter);
    }

    /** 复用已经装配好的引擎（服务端插件里已有检测链路时用）。 */
    public LaplaceTargetFacade(DetectionEngine engine, ServerPluginConfig config) {
        this.engine = engine;
        this.config = config == null ? ServerPluginConfig.defaults() : config;
    }

    public String targetId() {
        return LaplaceTargetDescriptor.TARGET_ID;
    }

    /** 机器可读描述符：事件入口、探针能力位与阈值锚点。 */
    public LaplaceTargetDescriptor descriptor() {
        return LaplaceTargetDescriptor.builtin(config);
    }

    public List<LaplaceFlag> onMove(PlayerPosition from, PlayerPosition to) {
        return convert(engine.handleMove(from, to));
    }

    public List<LaplaceFlag> onCombat(CombatEvent event) {
        return convert(engine.handleCombat(event));
    }

    public List<LaplaceFlag> onPacket(PacketView packet) {
        return convert(engine.handlePacket(packet));
    }

    public List<LaplaceFlag> onInventory(String playerId, String action, long timestampMs) {
        return convert(engine.handleInventory(playerId, action, timestampMs));
    }

    public List<LaplaceFlag> onInteract(String playerId, Position position, String action, long timestampMs) {
        return convert(engine.handleInteract(playerId, position, action, timestampMs));
    }

    public List<LaplaceFlag> onClientBrand(String playerId, String brand) {
        return convert(engine.handleClientBrand(playerId, brand));
    }

    /** 平台侧已解析好 {@code pacc:mods} 通道时直接喂哈希，不用再走品牌回调。 */
    public List<LaplaceFlag> onModHashes(String playerId, List<String> modHashes) {
        return convert(engine.handleModHashes(playerId, modHashes));
    }

    public List<LaplaceFlag> onAddons(String playerId, List<AddonInfo> addons) {
        return convert(engine.handleAddons(playerId, addons));
    }

    public List<LaplaceFlag> onDataConsistency(
            String playerId, Map<String, String> clientData, Map<String, String> serverData) {
        return convert(engine.handleDataConsistency(playerId, clientData, serverData));
    }

    /** 服务端刻边界；平台侧按刻调用，包时序阈值据此自适应（§5.3 U-05）。 */
    public void onTick(long nowMs) {
        engine.handleTick(nowMs);
    }

    /** 会话结束 / 重连时清掉该玩家历史，避免跨会话状态污染。 */
    public void reset(String playerId) {
        engine.resetPlayer(playerId);
    }

    private static List<LaplaceFlag> convert(List<DetectionResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream().map(LaplaceFlag::from).toList();
    }
}
