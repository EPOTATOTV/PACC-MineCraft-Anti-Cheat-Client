package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.InspectSession;
import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.InspectSessionRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 查端结论强制与 30 分钟超时单测：缺/非法结论拒绝结束与解锁、各结论行为、
 * 超时标记 TIMEOUT 但不解除红屏。存储层用 Mockito 桩。
 */
class InspectServiceTest {

    private InspectSessionRepository sessionRepo;
    private AccountRepository accountRepo;
    private RedscreenAlertRepository alertRepo;
    private CheatRecordRepository cheatRepo;
    private OnlineStatusService online;
    private InspectSignalBus bus;
    private InspectService service;

    @BeforeEach
    void setUp() {
        sessionRepo = mock(InspectSessionRepository.class);
        accountRepo = mock(AccountRepository.class);
        alertRepo = mock(RedscreenAlertRepository.class);
        cheatRepo = mock(CheatRecordRepository.class);
        online = mock(OnlineStatusService.class);
        bus = mock(InspectSignalBus.class);
        service = new InspectService(sessionRepo, accountRepo, alertRepo, cheatRepo, online, bus,
                mock(SessionStateService.class));
    }

    private InspectSession activeSession() {
        return InspectSession.builder()
                .sessionId("s1").pteid("PT42").alertId("a1")
                .state("ACTIVE").expiresAt(Instant.now().plusSeconds(600)).build();
    }

    private Account account() {
        return Account.builder().pteid("PT42").reputation(50).build();
    }

    private RedscreenAlert pendingAlert() {
        return RedscreenAlert.builder().alertId("a1").pteid("PT42").state("PENDING_INSPECT").build();
    }

    @Test
    void concludeRejectsMissingConclusion() {
        assertThrows(IllegalArgumentException.class, () -> service.conclude("s1", null, ""));
        assertThrows(IllegalArgumentException.class, () -> service.conclude("s1", "   ", ""));
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void concludeRejectsUnknownConclusion() {
        assertThrows(IllegalArgumentException.class, () -> service.conclude("s1", "SOMETHING_ELSE", ""));
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void legacyConfirmedAliasNormalizesToConfirmedCheat() {
        assertEquals("CONFIRMED_CHEAT", InspectService.normalizeConclusion("confirmed"));
        assertEquals("FALSE_POSITIVE", InspectService.normalizeConclusion("false_positive"));
        assertEquals("PENDING", InspectService.normalizeConclusion("pending"));
    }

    @Test
    void concludeConfirmedCheatResolvesRedscreen() {
        InspectSession s = activeSession();
        RedscreenAlert a = pendingAlert();
        when(sessionRepo.findById("s1")).thenReturn(Optional.of(s));
        when(accountRepo.findById("PT42")).thenReturn(Optional.of(account()));
        when(alertRepo.findById("a1")).thenReturn(Optional.of(a));

        service.conclude("s1", "CONFIRMED_CHEAT", "证据确凿");

        assertEquals("DONE", s.getState());
        assertEquals("CONFIRMED_CHEAT", s.getConclusion());
        assertEquals("CONFIRMED", a.getState());
        assertEquals("CONFIRMED_CHEAT", a.getInspectConclusion());
        assertTrue(s.getAuditLog().contains("conclusion=CONFIRMED_CHEAT"));
        verify(bus).sendToPlayer(eq("s1"), contains("inspect_bye"));
        verify(bus).sendToAdmin(eq("s1"), contains("inspect_closed"));
    }

    @Test
    void concludeFalsePositiveRevokesRecordAndRestores() {
        InspectSession s = activeSession();
        RedscreenAlert a = pendingAlert();
        Account acc = account();
        when(sessionRepo.findById("s1")).thenReturn(Optional.of(s));
        when(accountRepo.findById("PT42")).thenReturn(Optional.of(acc));
        when(alertRepo.findById("a1")).thenReturn(Optional.of(a));

        service.conclude("s1", "FALSE_POSITIVE", "误报");

        assertEquals("FALSE_POSITIVE", a.getState());
        assertEquals("normal", acc.getStatus());
        verify(cheatRepo).revokeByAlert("a1");
    }

    @Test
    void concludePendingEndsSessionWithoutUnlock() {
        InspectSession s = activeSession();
        RedscreenAlert a = pendingAlert();
        when(sessionRepo.findById("s1")).thenReturn(Optional.of(s));
        when(accountRepo.findById("PT42")).thenReturn(Optional.of(account()));

        service.conclude("s1", "PENDING", "待分析");

        assertEquals("DONE", s.getState());
        assertEquals("PENDING", s.getConclusion());
        // PENDING 不改红屏：仍锁定，未解锁
        assertEquals("PENDING_INSPECT", a.getState());
        verify(alertRepo, never()).save(any());
    }

    @Test
    void expireTimedOutMarksTimeoutWithoutUnlock() {
        InspectSession s = activeSession();
        s.setExpiresAt(Instant.now().minusSeconds(120));
        when(sessionRepo.findByStateAndExpiresAtBefore(eq("ACTIVE"), any())).thenReturn(List.of(s));

        int expired = service.expireTimedOut();

        assertEquals(1, expired);
        assertEquals("TIMEOUT", s.getState());
        assertTrue(s.getAuditLog().contains("timeout"));
        // 超时不自动解除红屏
        verify(alertRepo, never()).save(any());
        verify(bus).sendToPlayer(eq("s1"), contains("inspect_bye"));
        verify(bus).sendToAdmin(eq("s1"), contains("inspect_closed"));
    }

    @Test
    void expireTimedOutNoopWhenNone() {
        when(sessionRepo.findByStateAndExpiresAtBefore(eq("ACTIVE"), any())).thenReturn(List.of());

        assertEquals(0, service.expireTimedOut());
        verify(bus, never()).sendToPlayer(anyString(), anyString());
    }
}