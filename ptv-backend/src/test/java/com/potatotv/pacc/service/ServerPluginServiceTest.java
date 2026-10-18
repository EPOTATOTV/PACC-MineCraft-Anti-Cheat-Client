package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.domain.ServerPluginEvent;
import com.potatotv.pacc.domain.ServerPluginPunishment;
import com.potatotv.pacc.domain.ServerPluginRegistration;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import com.potatotv.pacc.repository.ServerPluginEventRepository;
import com.potatotv.pacc.repository.ServerPluginPunishmentRepository;
import com.potatotv.pacc.repository.ServerPluginRegistrationRepository;
import com.potatotv.pacc.service.alert.AdminAlertWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务端插件通道服务：注册 upsert、检测事件联动红屏、处置留痕校验、信誉聚合与配置下发。
 */
class ServerPluginServiceTest {

    private ServerPluginRegistrationRepository registrationRepository;
    private ServerPluginEventRepository eventRepository;
    private ServerPluginPunishmentRepository punishmentRepository;
    private AccountRepository accountRepository;
    private RedscreenAlertRepository alertRepository;
    private RedscreenService redscreenService;

    private ServerPluginService service;

    @BeforeEach
    void setUp() {
        registrationRepository = mock(ServerPluginRegistrationRepository.class);
        eventRepository = mock(ServerPluginEventRepository.class);
        punishmentRepository = mock(ServerPluginPunishmentRepository.class);
        accountRepository = mock(AccountRepository.class);
        alertRepository = mock(RedscreenAlertRepository.class);
        redscreenService = mock(RedscreenService.class);

        service = new ServerPluginService(registrationRepository, eventRepository, punishmentRepository,
                accountRepository, alertRepository, redscreenService,
                mock(AdminAlertWebhookService.class));
        ReflectionTestUtils.setField(service, "configVersion", "1");
        ReflectionTestUtils.setField(service, "punishmentMode", 0);
        ReflectionTestUtils.setField(service, "enabledChecksCsv", "MOVE,COMBAT");
        ReflectionTestUtils.setField(service, "confidenceThreshold", 60);
        ReflectionTestUtils.setField(service, "suspiciousLow", 70);
        ReflectionTestUtils.setField(service, "redscreenThreshold", 85);
    }

    @Test
    void registerUpsertsAndNormalizesType() {
        when(registrationRepository.findById("s1")).thenReturn(Optional.empty());

        service.register(new ServerPluginService.RegisterRequest(
                "s1", "paper", "6.0.0", "1.21.1", "Test Server", 100));

        ArgumentCaptor<ServerPluginRegistration> captor = ArgumentCaptor.forClass(ServerPluginRegistration.class);
        verify(registrationRepository).save(captor.capture());
        assertEquals("PAPER", captor.getValue().getServerType());
        assertEquals(100, captor.getValue().getMaxPlayers());
    }

    @Test
    void registerRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> service.register(
                new ServerPluginService.RegisterRequest("s1", "FORGE", "6.0.0", "1.21.1", "n", 10)));
    }

    @Test
    void highConfidenceEventTriggersRedscreen() {
        RedscreenAlert alert = RedscreenAlert.builder().alertId("alert_1").cheatType("Fly").pteid("PT1").build();
        when(redscreenService.decideAndHandle(eq("PT1"), eq("Fly"), anyInt(), anyString(), anyString()))
                .thenReturn(alert);

        ServerPluginService.EventAck ack = service.recordEvent(new ServerPluginService.EventRequest(
                "s1", "uuid-1", "Alice", "PT1", "MOVE", "Fly", 90, "detail", 1L));

        assertTrue(ack.redscreenTriggered());
        assertEquals("alert_1", ack.alertId());
        verify(eventRepository).save(any(ServerPluginEvent.class));
    }

    @Test
    void lowConfidenceEventDoesNotTriggerRedscreen() {
        ServerPluginService.EventAck ack = service.recordEvent(new ServerPluginService.EventRequest(
                "s1", "uuid-1", "Bob", "PT1", "MOVE", "Fly", 50, "detail", 1L));

        assertFalse(ack.redscreenTriggered());
        verify(redscreenService, never()).decideAndHandle(anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void eventWithoutPteidSkipsRedscreen() {
        ServerPluginService.EventAck ack = service.recordEvent(new ServerPluginService.EventRequest(
                "s1", "uuid-1", "Carol", null, "COMBAT", "KillAura", 99, "detail", 1L));

        assertFalse(ack.redscreenTriggered());
        verify(redscreenService, never()).decideAndHandle(anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void punishmentRejectsUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> service.recordPunishment(
                new ServerPluginService.PunishmentRequest("s1", "uuid-1", "MUTE", "reason", 0, "ACC_AUTO", 1L)));
    }

    @Test
    void punishmentIsRecorded() {
        service.recordPunishment(new ServerPluginService.PunishmentRequest(
                "s1", "uuid-1", "kick", "Fly", 0, "ACC_AUTO", 1L));
        ArgumentCaptor<ServerPluginPunishment> captor = ArgumentCaptor.forClass(ServerPluginPunishment.class);
        verify(punishmentRepository).save(captor.capture());
        assertEquals("KICK", captor.getValue().getPunishmentType());
    }

    @Test
    void configDefaultsToAlertOnly() {
        ServerPluginService.ConfigResponse cfg = service.config();
        assertEquals(0, cfg.punishmentMode());
        assertEquals(List.of("MOVE", "COMBAT"), cfg.enabledChecks());
        assertEquals("1", cfg.configVersion());
    }

    @Test
    void reputationAggregatesAccountAndRecentCheats() {
        Account acc = Account.builder().pteid("PT1").email("a@b.c").passwordHash("x")
                .reputation(40).totalRedscreen(3).build();
        when(accountRepository.findById("PT1")).thenReturn(Optional.of(acc));
        when(alertRepository.findTop10ByPteidOrderByOccurredAtDesc("PT1")).thenReturn(List.of(
                RedscreenAlert.builder().cheatType("Fly").build(),
                RedscreenAlert.builder().cheatType("Fly").build(),
                RedscreenAlert.builder().cheatType("KillAura").build()));

        ServerPluginService.ReputationResponse r = service.reputation("PT1", "uuid-1");

        assertEquals(40, r.reputationScore());
        assertEquals(3, r.redscreenCount());
        assertEquals(List.of("Fly", "KillAura"), r.recentCheats());
        assertTrue(r.hasClient());
    }

    @Test
    void reputationWithoutAccountReturnsTrustedDefaults() {
        ServerPluginService.ReputationResponse r = service.reputation(null, "uuid-1");
        assertEquals(100, r.reputationScore());
        assertEquals(0, r.redscreenCount());
        assertFalse(r.hasClient());
    }
}
