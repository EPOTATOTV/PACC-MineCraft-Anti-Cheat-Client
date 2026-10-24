package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.cluster.ClusterLock;
import com.potatotv.pacc.cluster.RedisClient;
import com.potatotv.pacc.domain.alert.AlertNotifyQueue;
import com.potatotv.pacc.repository.AlertNotifyQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-07 聚合告警投递：HELD 到期提升、P0/P1 走安全告警、P2/P3 走透明度告警。
 */
class AlertNotifyDispatcherTest {

    private AlertNotifyQueueRepository repository;
    private AlertNotifyDeliveryService deliveryService;
    private AlertNotifyDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        repository = mock(AlertNotifyQueueRepository.class);
        deliveryService = mock(AlertNotifyDeliveryService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisClient> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        dispatcher = new AlertNotifyDispatcher(repository, deliveryService, new ClusterLock(redisProvider));
    }

    @Test
    void dueQueueIsHandedToDeliveryService() {
        AlertNotifyQueue security = queue("q1", "P0", AlertNotifyQueue.ST_PENDING);
        AlertNotifyQueue transparency = queue("q2", "P3", AlertNotifyQueue.ST_PENDING);
        when(repository.findByStatusOrderByScheduledAtAsc(AlertNotifyQueue.ST_HELD))
                .thenReturn(List.of());
        when(repository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                eq(AlertNotifyQueue.ST_PENDING), any())).thenReturn(List.of(security, transparency));

        dispatcher.dispatchDue();

        verify(deliveryService).deliver("q1");
        verify(deliveryService).deliver("q2");
    }

    @Test
    void heldQueueIsPromotedWhenDue() {
        AlertNotifyQueue held = queue("q3", "P3", AlertNotifyQueue.ST_HELD);
        held.setScheduledAt(Instant.now().minusSeconds(60));
        when(repository.findByStatusOrderByScheduledAtAsc(AlertNotifyQueue.ST_HELD))
                .thenReturn(List.of(held));
        when(repository.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                eq(AlertNotifyQueue.ST_PENDING), any())).thenReturn(List.of());

        dispatcher.dispatchDue();

        assertEquals(AlertNotifyQueue.ST_PENDING, held.getStatus());
        verify(repository).save(held);
    }

    @Test
    void categoryMappingIsDepthBased() {
        assertEquals(AlertCategory.SECURITY, AlertNotifyDispatcher.categoryOf("P0"));
        assertEquals(AlertCategory.SECURITY, AlertNotifyDispatcher.categoryOf("p1"));
        assertEquals(AlertCategory.TRANSPARENCY, AlertNotifyDispatcher.categoryOf("P2"));
        assertEquals(AlertCategory.TRANSPARENCY, AlertNotifyDispatcher.categoryOf("P3"));
        assertEquals(AlertCategory.TRANSPARENCY, AlertNotifyDispatcher.categoryOf(null));
    }

    private static AlertNotifyQueue queue(String id, String priority, String status) {
        return AlertNotifyQueue.builder()
                .id(id)
                .groupId("g-" + id)
                .priority(priority)
                .status(status)
                .channel("dashboard")
                .scheduledAt(Instant.now().minusSeconds(1))
                .payload("payload-" + id)
                .build();
    }
}
