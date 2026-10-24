package com.potatotv.pacc.service;

import com.potatotv.pacc.cluster.RedisClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R-06：写入侧计数与快照口径。 */
class RealtimeMetricsServiceTest {

    @SuppressWarnings("unchecked")
    private static RealtimeMetricsService serviceWith(RedisClient redis) {
        ObjectProvider<RedisClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return new RealtimeMetricsService(provider);
    }

    @SuppressWarnings("unchecked")
    @Test
    void detectionUpdatesDayMinuteAndRiskCounters() {
        RedisClient redis = mock(RedisClient.class);
        when(redis.incr(anyString())).thenReturn(1L);
        RealtimeMetricsService service = serviceWith(redis);

        service.recordDetection(80);

        verify(redis, atLeastOnce()).incr(org.mockito.ArgumentMatchers.argThat(
                key -> key.startsWith("pacc:metrics:detections:day:")));
        verify(redis, atLeastOnce()).incr(org.mockito.ArgumentMatchers.argThat(
                key -> key.startsWith("pacc:metrics:detections:min:")));
        verify(redis).incrBy(org.mockito.ArgumentMatchers.argThat(
                key -> key.startsWith("pacc:metrics:risk-sum:day:")), eq(80L));
    }

    @SuppressWarnings("unchecked")
    @Test
    void snapshotAggregatesDayAndRollingWindow() {
        RedisClient redis = mock(RedisClient.class);
        when(redis.get(anyString())).thenReturn("10");
        when(redis.mget(any(String[].class))).thenReturn(java.util.Collections.nCopies(30, "1"));
        RealtimeMetricsService service = serviceWith(redis);

        RealtimeMetricsService.Snapshot snapshot = service.snapshot(5);

        assertEquals(5L, snapshot.online());
        assertEquals(10L, snapshot.detectionsToday());
        assertEquals(30L, snapshot.detections30m());
        assertEquals(1.0, snapshot.avgRisk(), 1e-9);
        assertEquals(100.0, snapshot.detectionRatePercent(), 1e-9);
    }

    @SuppressWarnings("unchecked")
    @Test
    void withoutRedisSnapshotReturnsNull() {
        ObjectProvider<RedisClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RealtimeMetricsService service = new RealtimeMetricsService(provider);

        assertNull(service.snapshot(3));
        service.recordDetection(90); // no-op 不应抛错
        assertTrue(true);
    }
}
