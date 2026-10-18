package com.potatotv.pacc.server.core.laplace;

import com.potatotv.pacc.detection.server.packet.PacketTiming;
import com.potatotv.pacc.detection.server.packet.TransactionSync;
import com.potatotv.pacc.detection.server.perf.PerformanceCalibrator;
import com.potatotv.pacc.server.core.check.PacketCheck;
import com.potatotv.pacc.server.core.check.PhaseCheck;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 拉普拉斯精灵的**被测目标描述符**（机器可读契约）。
 *
 * <p>拉普拉斯是独立项目（github.com/EPOTATOTV/LaplaceSprite），本仓库只做「可被它接入」的适配面，
 * 不复现平台功能。平台侧的适配器按它自己的 14.8 六步流程编写，需要知道三件事：
 * 这个目标能喂哪些事件、能给出哪些探针能力、阈值锚点是多少。这份描述符把三件事固化下来，
 * 平台侧可以直接读取（同内容的 JSON 在 {@code resources/pacc-laplace-target.json}），也可以
 * 在适配器里调 {@link #builtin(ServerPluginConfig)} 拿同一份数据做断言。
 *
 * <p>阈值锚点是关键：拉普拉斯的作弊注入幅度 = 目标引擎阈值 × 档位系数。锚点写死一份在平台侧、
 * 一份在引擎侧，两边一漂移，检出率数据就失去意义。这里集中导出，平台侧的 crosscheck 测试可以
 * 直接比。
 */
public record LaplaceTargetDescriptor(
        String targetId,
        String kind,
        String version,
        String contractVersion,
        List<String> eventClasses,
        Map<String, Boolean> probeCapabilities,
        Map<String, Double> thresholdAnchors,
        String entrypoint,
        String artifact,
        String note) {

    /** 目标 id，平台侧 AdapterRegistry 用。 */
    public static final String TARGET_ID = "pacc-server-core";

    /** 目标类型：检测引擎（进程内直连口径）。 */
    public static final String KIND = "detection-engine";

    /** 事件流契约版本。改事件集合或字段语义时必须升版本，平台侧据此拒绝不兼容目标。 */
    public static final String CONTRACT_VERSION = "laplace-event-contract-v1";

    /** PACC 内核版本，与 {@code pacc-server-core/pom.xml} 的 version 对齐。 */
    public static final String VERSION = "6.0.0";

    public LaplaceTargetDescriptor {
        eventClasses = eventClasses == null ? List.of() : List.copyOf(eventClasses);
        // 保留插入顺序：toJson 与发布出去的 JSON 要逐字节对齐，Map.copyOf 会打乱顺序
        probeCapabilities = probeCapabilities == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(probeCapabilities));
        thresholdAnchors = thresholdAnchors == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(thresholdAnchors));
    }

    /** 本目标暴露给平台的检测入口类。 */
    public static final String ENTRYPOINT = "com.potatotv.pacc.server.core.laplace.LaplaceTargetFacade";

    /**
     * 生成描述符。
     *
     * @param config 运行配置；阈值锚点取自它，保证平台侧锚定的是**当前生效**的阈值
     */
    public static LaplaceTargetDescriptor builtin(ServerPluginConfig config) {
        ServerPluginConfig effective = config == null ? ServerPluginConfig.defaults() : config;
        return new LaplaceTargetDescriptor(
                TARGET_ID,
                KIND,
                VERSION,
                CONTRACT_VERSION,
                defaultEventClasses(),
                defaultProbeCapabilities(),
                thresholdAnchors(effective),
                ENTRYPOINT,
                "com.potatotv.pacc:pacc-server-core:" + VERSION,
                "服务端进程内检测目标；客户端探针（进程/模块/内存/USB 等）由 ptv-client 经 PTV 通道上报，"
                        + "不在本目标的进程内契约里，故相关能力位一律为 false");
    }

    /** 归一化事件入口，对应平台事件流的 10 类核心事件 + 刻与重置。 */
    public static List<String> defaultEventClasses() {
        return List.of(
                "Move", "Combat", "Packet", "Inventory", "Interact",
                "ClientBrand", "Mods", "ModHashes", "Addons", "DataConsistency",
                "Tick", "Reset");
    }

    /** 探针 / 能力位。{@code false} 表示「该能力不由本目标提供」，不是「查了没有」。 */
    public static Map<String, Boolean> defaultProbeCapabilities() {
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("server_packet_stream", true);
        capabilities.put("server_block_probe", true);
        capabilities.put("server_transaction_sync", true);
        capabilities.put("server_evidence_chain", true);
        capabilities.put("server_performance_calibration", true);
        capabilities.put("client_process_scan", false);
        capabilities.put("client_module_scan", false);
        capabilities.put("client_memory_scan", false);
        capabilities.put("client_usb_scan", false);
        capabilities.put("client_registry_scan", false);
        return capabilities;
    }

    /** 阈值锚点。平台侧注入幅度以这些值为基准，避免两边阈值各写一份后漂移。 */
    public static Map<String, Double> thresholdAnchors(ServerPluginConfig config) {
        Map<String, Double> anchors = new LinkedHashMap<>();
        anchors.put("move.max-horizontal-speed-per-tick", config.maxHorizontalSpeedPerTick());
        anchors.put("move.max-vertical-climb-per-tick", config.maxVerticalClimbPerTick());
        anchors.put("move.fly-airborne-ticks-trigger", (double) config.flyAirborneTicksTrigger());
        anchors.put("combat.max-reach-distance", config.maxReachDistance());
        anchors.put("combat.max-cps", (double) config.maxCps());
        anchors.put("interaction.max-place-per-tick", (double) config.maxPlacePerTick());
        anchors.put("inventory.confidence-threshold", (double) config.confidenceThreshold());
        anchors.put("packet.teleport-threshold", PacketCheck.TELEPORT_THRESHOLD);
        anchors.put("packet.no-fall-min-drop", PacketCheck.NO_FALL_MIN_DROP);
        anchors.put("packet.timer-threshold", PacketTiming.TIMER_THRESHOLD);
        anchors.put("packet.transaction-ack-rate", TransactionSync.ACK_RATE_THRESHOLD);
        anchors.put("phase.required-strikes", (double) PhaseCheck.REQUIRED_STRIKES);
        anchors.put("packet.nominal-tick-ms", PerformanceCalibrator.NOMINAL_TICK_MS);
        return anchors;
    }

    /**
     * 序列化成 JSON。手写而不是引第三方库：本模块的硬约束是运行时零第三方依赖，
     * 描述符结构又是固定且扁平的，没必要为此背一个 JSON 库。
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        appendString(sb, "targetId", targetId, 1, true);
        appendString(sb, "kind", kind, 1, true);
        appendString(sb, "version", version, 1, true);
        appendString(sb, "contractVersion", contractVersion, 1, true);
        sb.append("  \"eventClasses\": [");
        for (int i = 0; i < eventClasses.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(eventClasses.get(i)).append('"');
        }
        sb.append("],\n");
        sb.append("  \"probeCapabilities\": {");
        int index = 0;
        for (Map.Entry<String, Boolean> entry : probeCapabilities.entrySet()) {
            if (index++ > 0) {
                sb.append(", ");
            }
            sb.append('"').append(entry.getKey()).append("\": ").append(entry.getValue());
        }
        sb.append("},\n");
        sb.append("  \"thresholdAnchors\": {");
        index = 0;
        for (Map.Entry<String, Double> entry : thresholdAnchors.entrySet()) {
            if (index++ > 0) {
                sb.append(", ");
            }
            sb.append('"').append(entry.getKey()).append("\": ")
                    .append(String.format(Locale.ROOT, "%.6f", entry.getValue()));
        }
        sb.append("},\n");
        appendString(sb, "entrypoint", entrypoint, 1, true);
        appendString(sb, "artifact", artifact, 1, true);
        appendString(sb, "note", note, 1, false);
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String key, String value, int indent, boolean trailingComma) {
        sb.append("  ".repeat(indent)).append('"').append(key).append("\": \"")
                .append(escape(value)).append('"').append(trailingComma ? ",\n" : "\n");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
