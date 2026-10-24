package com.potatotv.pacc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.cluster.RedisClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R-03：无 Redis 时的进程内缓存命中、失效与 TTL。 */
class HotCacheServiceTest {

    private HotCacheService cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<RedisClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        cache = new HotCacheService(provider, new ObjectMapper());
    }

    @Test
    void secondReadHitsLocalCache() {
        AtomicInteger loads = new AtomicInteger();
        TypeReference<List<String>> type = new TypeReference<>() { };
        List<String> first = cache.getOrLoad("k", Duration.ofMinutes(5), type,
                () -> List.of("v" + loads.incrementAndGet()));
        List<String> second = cache.getOrLoad("k", Duration.ofMinutes(5), type,
                () -> List.of("v" + loads.incrementAndGet()));

        assertEquals(List.of("v1"), first);
        assertEquals(List.of("v1"), second);
        assertEquals(1, loads.get());
    }

    @Test
    void evictForcesReload() {
        AtomicInteger loads = new AtomicInteger();
        TypeReference<List<String>> type = new TypeReference<>() { };
        cache.getOrLoad("k", Duration.ofMinutes(5), type, () -> List.of("v" + loads.incrementAndGet()));
        cache.evict("k");
        List<String> reloaded = cache.getOrLoad("k", Duration.ofMinutes(5), type,
                () -> List.of("v" + loads.incrementAndGet()));

        assertEquals(List.of("v2"), reloaded);
    }

    @Test
    void expiredEntryIsReloaded() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        TypeReference<List<String>> type = new TypeReference<>() { };
        cache.getOrLoad("k", Duration.ofMillis(1), type, () -> List.of("v" + loads.incrementAndGet()));
        Thread.sleep(5L);
        List<String> reloaded = cache.getOrLoad("k", Duration.ofMillis(1), type,
                () -> List.of("v" + loads.incrementAndGet()));

        assertEquals(List.of("v2"), reloaded);
    }
}
