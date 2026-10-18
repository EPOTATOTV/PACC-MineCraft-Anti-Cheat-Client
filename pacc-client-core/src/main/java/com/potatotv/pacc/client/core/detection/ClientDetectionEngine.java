package com.potatotv.pacc.client.core.detection;

import com.potatotv.pacc.client.core.model.ClientCheckType;
import com.potatotv.pacc.client.core.model.ClientDetectionEvent;
import com.potatotv.pacc.client.core.model.ClientDetectionResult;
import com.potatotv.pacc.client.core.model.ClientIdentity;
import com.potatotv.pacc.client.core.model.CombatSample;
import com.potatotv.pacc.client.core.model.PlayerMovement;
import com.potatotv.pacc.client.core.redscreen.KeyboardLockController;
import com.potatotv.pacc.client.core.redscreen.RedScreenAlert;
import com.potatotv.pacc.client.core.redscreen.RedScreenController;

import java.lang.System.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 客户端检测引擎：把加载器层喂进来的本地数据分发给检测器，聚合结果并做两件事——上报 PTV 与触发红屏。
 *
 * <p>玩家端没有封禁 / 踢出：高强度命中只会显示红屏并锁定输入，事件经 PTV 独立通道留痕。
 * 上报或红屏实现抛异常都不会打断检测流程。
 */
public final class ClientDetectionEngine {

    private static final Logger LOG = System.getLogger(ClientDetectionEngine.class.getName());

    /** 达到该置信度才触发红屏 + 输入锁定。 */
    private static final int REDSCREEN_CONFIDENCE_THRESHOLD = 85;

    /** 红屏默认持续时长（毫秒）。 */
    private static final long REDSCREEN_DURATION_MS = 30_000L;

    /** 玩家可见文案：不披露具体检测项（P-03）。 */
    private static final String PLAYER_ALERT_MESSAGE = "检测到违规行为，已记录并进入核查。";

    private final ClientIdentity identity;
    private final MoveDetector moveDetector;
    private final CombatDetector combatDetector;
    private final ModListScanner modListScanner;
    private final Consumer<ClientDetectionEvent> reporter;
    private final RedScreenController redScreen;
    private final KeyboardLockController keyboardLock;

    public ClientDetectionEngine(
            ClientIdentity identity,
            Consumer<ClientDetectionEvent> reporter,
            RedScreenController redScreen,
            KeyboardLockController keyboardLock) {
        this(identity, new MoveDetector(), new CombatDetector(), new ModListScanner(),
                reporter, redScreen, keyboardLock);
    }

    public ClientDetectionEngine(
            ClientIdentity identity,
            MoveDetector moveDetector,
            CombatDetector combatDetector,
            ModListScanner modListScanner,
            Consumer<ClientDetectionEvent> reporter,
            RedScreenController redScreen,
            KeyboardLockController keyboardLock) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.moveDetector = Objects.requireNonNull(moveDetector, "moveDetector");
        this.combatDetector = Objects.requireNonNull(combatDetector, "combatDetector");
        this.modListScanner = Objects.requireNonNull(modListScanner, "modListScanner");
        this.reporter = reporter;
        this.redScreen = redScreen;
        this.keyboardLock = keyboardLock;
    }

    public List<ClientDetectionResult> onMove(PlayerMovement from, PlayerMovement to) {
        return handle(moveDetector.onMove(from, to));
    }

    public List<ClientDetectionResult> onAttack(CombatSample sample) {
        return handle(combatDetector.onAttack(sample));
    }

    /** 扫描本机已加载模组列表。 */
    public List<ClientDetectionResult> scanMods(List<String> modIdentifiers) {
        return handle(modListScanner.scan(modIdentifiers));
    }

    /** 本地玩家换图 / 重生时重置检测器历史状态。 */
    public void reset() {
        moveDetector.reset();
        combatDetector.reset();
    }

    private List<ClientDetectionResult> handle(List<ClientDetectionResult> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ClientDetectionResult> violations = new ArrayList<>();
        for (ClientDetectionResult result : raw) {
            if (result == null || !result.violation()) {
                continue;
            }
            violations.add(result);
            safeReport(result);
            if (result.confidence() >= REDSCREEN_CONFIDENCE_THRESHOLD) {
                triggerRedScreen(result);
            }
        }
        return violations;
    }

    private void safeReport(ClientDetectionResult result) {
        if (reporter == null) {
            return;
        }
        ClientDetectionEvent event = new ClientDetectionEvent(
                identity.pteid(), identity.playerName(), identity.clientVersion(),
                result.checkType(), result.cheatType(), result.confidence(), result.detail(),
                result.timestampMs());
        try {
            reporter.accept(event);
        } catch (RuntimeException ex) {
            LOG.log(Logger.Level.WARNING, "上报检测事件失败，已忽略", ex);
        }
    }

    private void triggerRedScreen(ClientDetectionResult result) {
        if (redScreen == null) {
            return;
        }
        RedScreenAlert alert = new RedScreenAlert(
                UUID.randomUUID().toString(),
                PLAYER_ALERT_MESSAGE,
                REDSCREEN_DURATION_MS);
        try {
            redScreen.show(alert);
            if (keyboardLock != null) {
                keyboardLock.lock();
            }
        } catch (RuntimeException ex) {
            LOG.log(Logger.Level.WARNING, "触发红屏失败，已忽略", ex);
        }
    }

    /** 供加载器层判断是否需要接管：{@code MOD_LIST} 命中即可直接红屏，无需等待移动 / 战斗证据。 */
    public static boolean isRedScreenConfidence(int confidence) {
        return confidence >= REDSCREEN_CONFIDENCE_THRESHOLD;
    }

    /** 检查项分类，便于加载器层按类型分流。 */
    public static ClientCheckType[] checkTypes() {
        return ClientCheckType.values();
    }
}
