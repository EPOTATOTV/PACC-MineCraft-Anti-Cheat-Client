package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.domain.DeviceRecord;
import com.potatotv.pacc.domain.PlayerHardwareFingerprint;
import com.potatotv.pacc.domain.ServerPluginEvent;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.repository.DeviceRecordRepository;
import com.potatotv.pacc.repository.PlayerHardwareFingerprintRepository;
import com.potatotv.pacc.repository.ServerPluginEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-06 跨端证据链：两侧事件合并、按时间倒序、设备指纹、空 PTEID 短路。
 */
class CrossEvidenceServiceTest {

    private DetectionEventRepository detectionEventRepository;
    private ServerPluginEventRepository serverPluginEventRepository;
    private DeviceRecordRepository deviceRecordRepository;
    private PlayerHardwareFingerprintRepository hardwareFingerprintRepository;
    private CrossEvidenceService service;

    @BeforeEach
    void setUp() {
        detectionEventRepository = mock(DetectionEventRepository.class);
        serverPluginEventRepository = mock(ServerPluginEventRepository.class);
        deviceRecordRepository = mock(DeviceRecordRepository.class);
        hardwareFingerprintRepository = mock(PlayerHardwareFingerprintRepository.class);
        service = new CrossEvidenceService(detectionEventRepository, serverPluginEventRepository,
                deviceRecordRepository, hardwareFingerprintRepository);
    }

    @Test
    void mergesClientAndServerEventsByTimeDescending() {
        when(detectionEventRepository.findTop200ByPteidOrderByOccurredAtDesc("PT1234ABCD"))
                .thenReturn(List.of(client("PT1234ABCD", "c1", Instant.parse("2026-10-18T03:00:00Z"))));
        when(serverPluginEventRepository.findTop200ByPteidOrderByReceivedAtDesc("PT1234ABCD"))
                .thenReturn(List.of(server("PT1234ABCD", "s1", Instant.parse("2026-10-18T04:00:00Z"), 92)));

        CrossEvidenceChain chain = service.chain("PT1234ABCD");

        assertEquals(1, chain.clientEventCount());
        assertEquals(1, chain.serverEventCount());
        assertEquals(2, chain.items().size());
        assertEquals("SERVER", chain.items().get(0).source(), "较新的服务端事件排在前");
        assertEquals("CLIENT", chain.items().get(1).source());
    }

    @Test
    void mapsClientEvidenceAndServerConfidence() {
        when(detectionEventRepository.findTop200ByPteidOrderByOccurredAtDesc("PT1234ABCD"))
                .thenReturn(List.of(client("PT1234ABCD", "c1", Instant.parse("2026-10-18T03:00:00Z"))));
        when(serverPluginEventRepository.findTop200ByPteidOrderByReceivedAtDesc("PT1234ABCD"))
                .thenReturn(List.of(server("PT1234ABCD", "s1", Instant.parse("2026-10-18T04:00:00Z"), 92)));

        List<CrossEvidenceItem> items = service.chain("PT1234ABCD").items();

        CrossEvidenceItem server = items.get(0);
        assertEquals("critical", server.severity());
        assertEquals(92, server.riskScore());
        assertEquals("COMBAT", server.checkType());
        assertEquals("SPEED", server.eventType());
        assertTrue(server.redscreenTriggered());

        CrossEvidenceItem client = items.get(1);
        assertEquals("high", client.severity());
        assertEquals("signature-hit", client.detail());
        assertEquals(88, client.riskScore());
        assertEquals("cheat.exe", client.processName());
        assertEquals("0x7FF", client.memoryRegion());
        assertEquals("signature-hit", client.signatureHit());
        assertEquals("6.0.0", client.clientVersion());
    }

    @Test
    void includesDeviceFingerprints() {
        when(deviceRecordRepository.findByPteidOrderByLastLoginAtDesc("PT1234ABCD"))
                .thenReturn(List.of(DeviceRecord.builder()
                        .deviceId("dev-1")
                        .pteid("PT1234ABCD")
                        .deviceFingerprint("a".repeat(64))
                        .platform("WINDOWS")
                        .deviceName("DESKTOP-1")
                        .ip("127.0.0.1")
                        .firstLoginAt(Instant.parse("2026-10-01T00:00:00Z"))
                        .lastLoginAt(Instant.parse("2026-10-18T00:00:00Z"))
                        .active(true)
                        .build()));

        CrossEvidenceChain chain = service.chain("PT1234ABCD");

        assertEquals(1, chain.devices().size());
        CrossEvidenceDevice device = chain.devices().get(0);
        assertEquals("dev-1", device.deviceId());
        assertEquals("WINDOWS", device.platform());
        assertTrue(device.active());
        assertEquals(64, device.deviceFingerprint().length());
    }

    @Test
    void includesHardwareFingerprints() {
        when(hardwareFingerprintRepository.findTop300ByPteidOrderByLastSeenAtDesc("PT1234ABCD"))
                .thenReturn(List.of(PlayerHardwareFingerprint.builder()
                        .pteid("PT1234ABCD")
                        .fingerprintHash("b".repeat(64))
                        .firstSeenAt(Instant.parse("2026-09-01T00:00:00Z"))
                        .lastSeenAt(Instant.parse("2026-10-18T00:00:00Z"))
                        .seenCount(7)
                        .build()));

        CrossEvidenceChain chain = service.chain("PT1234ABCD");

        assertEquals(1, chain.hardwareFingerprints().size());
        CrossEvidenceHardware hardware = chain.hardwareFingerprints().get(0);
        assertEquals(64, hardware.fingerprintHash().length());
        assertEquals(7, hardware.seenCount());
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), hardware.firstSeenAt());
    }

    @Test
    void blankPteidShortCircuits() {
        CrossEvidenceChain chain = service.chain("   ");
        assertTrue(chain.items().isEmpty());
        assertTrue(chain.devices().isEmpty());
        assertTrue(chain.hardwareFingerprints().isEmpty());
        verify(detectionEventRepository, never()).findTop200ByPteidOrderByOccurredAtDesc("   ");
        verify(serverPluginEventRepository, never()).findTop200ByPteidOrderByReceivedAtDesc("   ");
        verify(deviceRecordRepository, never()).findByPteidOrderByLastLoginAtDesc("   ");
        verify(hardwareFingerprintRepository, never()).findTop300ByPteidOrderByLastSeenAtDesc("   ");
    }

    private static DetectionEvent client(String pteid, String id, Instant at) {
        return DetectionEvent.builder()
                .id(id)
                .pteid(pteid)
                .eventType("memory_tamper")
                .severity("high")
                .clientRiskScore(88)
                .evidence(new DetectionEvent.Evidence("cheat.exe", "0x7FF", "signature-hit", null))
                .clientVersion("6.0.0")
                .osInfo("Windows 11")
                .occurredAt(at)
                .build();
    }

    private static ServerPluginEvent server(String pteid, String id, Instant at, int confidence) {
        return ServerPluginEvent.builder()
                .id(id)
                .serverId("srv-1")
                .playerName("Steve")
                .playerUuid("uuid-1")
                .pteid(pteid)
                .checkType("COMBAT")
                .cheatType("SPEED")
                .confidence(confidence)
                .detail("速度异常")
                .eventTsMs(at.toEpochMilli())
                .receivedAt(at)
                .redscreenTriggered(true)
                .alertId("alert-1")
                .build();
    }
}
