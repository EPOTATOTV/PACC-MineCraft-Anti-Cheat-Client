package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.cluster.RedisClient;
import com.potatotv.pacc.domain.alert.AlertNotifyQueue;
import com.potatotv.pacc.repository.AlertNotifyQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R-02：队列项成功、重试与死信路径。 */
class AlertNotifyDeliveryServiceTest {

    private AlertNotifyQueueRepository repository;
    private AdminAlertWebhookService webhook;
    private AlertDispatchGuard guard;
    private AlertNotifyDeliveryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(AlertNotifyQueueRepository.class);
        webhook = mock(AdminAlertWebhookService.class);
        guard = mock(AlertDispatchGuard.class);
        ObjectProvider<RedisClient> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        service = new AlertNotifyDeliveryService(repository, webhook, guard, redisProvider);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
        ReflectionTestUtils.setField(service, "retryBackoffMs", 1000L);
        ReflectionTestUtils.setField(service, "retryBackoffMaxMs", 10_000L);
    }

    @Test
    void successfulDeliveryMarksSent() {
        AlertNotifyQueue item = pending("q1", 0);
        when(repository.findById("q1")).thenReturn(Optional.of(item));
        when(guard.tryAcquire("q1")).thenReturn(true);

        assertTrue(service.deliver("q1"));

        assertEquals(AlertNotifyQueue.ST_SENT, item.getStatus());
        verify(repository).save(item);
        verify(webhook).dispatch(AlertCategory.SECURITY, "PACC 聚合告警 P0", "payload-q1");
    }

    @Test
    void failedDeliverySchedulesRetryAndReleasesLease() {
        AlertNotifyQueue item = pending("q2", 0);
        when(repository.findById("q2")).thenReturn(Optional.of(item));
        when(guard.tryAcquire("q2")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("channel down"))
                .when(webhook).dispatch(any(), anyString(), anyString());

        assertFalse(service.deliver("q2"));

        assertEquals(AlertNotifyQueue.ST_PENDING, item.getStatus());
        assertEquals(1, item.getAttempts());
        assertTrue(item.getScheduledAt().isAfter(Instant.now()), "失败后应推迟重试时间");
        verify(guard).release("q2");
    }

    @Test
    void deliveryBeyondMaxAttemptsGoesDead() {
        AlertNotifyQueue item = pending("q3", 2);
        when(repository.findById("q3")).thenReturn(Optional.of(item));
        when(guard.tryAcquire("q3")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("channel down"))
                .when(webhook).dispatch(any(), anyString(), anyString());

        assertFalse(service.deliver("q3"));

        assertEquals(AlertNotifyQueue.ST_DEAD, item.getStatus());
        assertEquals(3, item.getAttempts());
    }

    @Test
    void duplicateLeaseIsSkipped() {
        AlertNotifyQueue item = pending("q4", 0);
        when(repository.findById("q4")).thenReturn(Optional.of(item));
        when(guard.tryAcquire("q4")).thenReturn(false);

        assertTrue(service.deliver("q4"));

        verify(webhook, never()).dispatch(any(), anyString(), anyString());
        assertEquals(AlertNotifyQueue.ST_PENDING, item.getStatus());
    }

    private static AlertNotifyQueue pending(String id, int attempts) {
        return AlertNotifyQueue.builder()
                .id(id)
                .groupId("g-" + id)
                .priority("P0")
                .status(AlertNotifyQueue.ST_PENDING)
                .scheduledAt(Instant.now().minusSeconds(1))
                .attempts(attempts)
                .payload("payload-" + id)
                .build();
    }
}
