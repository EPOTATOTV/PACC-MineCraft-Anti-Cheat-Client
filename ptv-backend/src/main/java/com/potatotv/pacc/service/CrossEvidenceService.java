package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.domain.DeviceRecord;
import com.potatotv.pacc.domain.PlayerHardwareFingerprint;
import com.potatotv.pacc.domain.ServerPluginEvent;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.repository.DeviceRecordRepository;
import com.potatotv.pacc.repository.PlayerHardwareFingerprintRepository;
import com.potatotv.pacc.repository.ServerPluginEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 跨端证据链聚合（P-06）。
 *
 * <p>玩家端事件、设备指纹与服务端插件事件是三条独立上报通道，唯一可用的关联键是 PTEID。
 * 本服务只做只读拼装：按 PTEID 取两侧最近 200 条、登录设备与玩家端硬件指纹摘要，统一成
 * {@link CrossEvidenceItem} / {@link CrossEvidenceDevice} / {@link CrossEvidenceHardware} 后返回。
 *
 * <p>服务端事件允许没有 PTEID（玩家未安装玩家端）。这类事件不会出现在本接口里——
 * 没有关联键就无法安全地归到某个 PTEID 名下，宁可留给服务端事件列表单独展示。
 */
@Service
@RequiredArgsConstructor
public class CrossEvidenceService {

    private static final int MAX_ITEMS_PER_SIDE = 200;

    private final DetectionEventRepository detectionEventRepository;
    private final ServerPluginEventRepository serverPluginEventRepository;
    private final DeviceRecordRepository deviceRecordRepository;
    private final PlayerHardwareFingerprintRepository hardwareFingerprintRepository;

    /** 查询同一 PTEID 的双端证据时间线、登录设备与硬件指纹。 */
    public CrossEvidenceChain chain(String pteid) {
        String key = pteid == null ? "" : pteid.trim();
        if (key.isEmpty()) {
            return new CrossEvidenceChain("", 0, 0, List.of(), List.of(), List.of());
        }
        List<DetectionEvent> clientEvents =
                detectionEventRepository.findTop200ByPteidOrderByOccurredAtDesc(key);
        List<ServerPluginEvent> serverEvents =
                serverPluginEventRepository.findTop200ByPteidOrderByReceivedAtDesc(key);
        List<DeviceRecord> devices = deviceRecordRepository.findByPteidOrderByLastLoginAtDesc(key);
        List<PlayerHardwareFingerprint> hardware =
                hardwareFingerprintRepository.findTop300ByPteidOrderByLastSeenAtDesc(key);

        List<CrossEvidenceItem> items = new ArrayList<>(
                Math.min(MAX_ITEMS_PER_SIDE * 2, clientEvents.size() + serverEvents.size()));
        for (DetectionEvent event : clientEvents) {
            items.add(fromClient(event));
        }
        for (ServerPluginEvent event : serverEvents) {
            items.add(fromServer(event));
        }
        items.sort(Comparator.comparing(CrossEvidenceItem::occurredAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new CrossEvidenceChain(key, clientEvents.size(), serverEvents.size(),
                devices.stream().map(CrossEvidenceService::fromDevice).toList(),
                hardware.stream().map(CrossEvidenceService::fromHardware).toList(),
                List.copyOf(items));
    }

    private static CrossEvidenceItem fromClient(DetectionEvent event) {
        DetectionEvent.Evidence evidence = event.getEvidence();
        return new CrossEvidenceItem(
                "CLIENT",
                event.getId(),
                event.getPteid(),
                null,
                null,
                event.getEventType(),
                null,
                event.getSeverity(),
                event.getClientRiskScore(),
                clientDetail(evidence),
                evidence == null ? null : evidence.getProcessName(),
                evidence == null ? null : evidence.getMemoryRegion(),
                evidence == null ? null : evidence.getSignatureHit(),
                event.getClientVersion(),
                event.getOsInfo(),
                event.getOccurredAt(),
                null,
                null,
                false);
    }

    private static CrossEvidenceItem fromServer(ServerPluginEvent event) {
        Instant occurred = event.getEventTsMs() > 0
                ? Instant.ofEpochMilli(event.getEventTsMs())
                : event.getReceivedAt();
        return new CrossEvidenceItem(
                "SERVER",
                event.getId(),
                event.getPteid(),
                event.getPlayerName(),
                event.getPlayerUuid(),
                event.getCheatType(),
                event.getCheckType(),
                severityOf(event.getConfidence()),
                event.getConfidence(),
                event.getDetail(),
                null,
                null,
                null,
                null,
                null,
                occurred,
                event.getAlertId(),
                event.getServerId(),
                event.isRedscreenTriggered());
    }

    private static CrossEvidenceDevice fromDevice(DeviceRecord device) {
        return new CrossEvidenceDevice(
                device.getDeviceId(),
                device.getDeviceFingerprint(),
                device.getPlatform(),
                device.getDeviceName(),
                device.getIp(),
                device.getFirstLoginAt(),
                device.getLastLoginAt(),
                device.isActive());
    }

    private static CrossEvidenceHardware fromHardware(PlayerHardwareFingerprint fingerprint) {
        return new CrossEvidenceHardware(
                fingerprint.getFingerprintHash(),
                fingerprint.getFirstSeenAt(),
                fingerprint.getLastSeenAt(),
                fingerprint.getSeenCount());
    }

    /** 玩家端详情只做展示用拼接，不把整段原始 JSON 直接回给前端。 */
    private static String clientDetail(DetectionEvent.Evidence evidence) {
        if (evidence == null) {
            return null;
        }
        if (evidence.getSignatureHit() != null && !evidence.getSignatureHit().isBlank()) {
            return evidence.getSignatureHit();
        }
        if (evidence.getProcessName() != null && !evidence.getProcessName().isBlank()) {
            return evidence.getProcessName();
        }
        return evidence.getDetailJson();
    }

    private static String severityOf(int confidence) {
        if (confidence >= 90) {
            return "critical";
        }
        if (confidence >= 85) {
            return "high";
        }
        if (confidence >= 70) {
            return "medium";
        }
        return "low";
    }
}
