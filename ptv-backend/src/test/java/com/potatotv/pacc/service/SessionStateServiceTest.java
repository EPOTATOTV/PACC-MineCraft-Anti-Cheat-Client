package com.potatotv.pacc.service;

import com.potatotv.pacc.cluster.RedisClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R-04：Redis 未启用时会话状态必须是安全的 no-op，不能让调用方 NPE。 */
class SessionStateServiceTest {

    private SessionStateService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<RedisClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        service = new SessionStateService(provider);
    }

    @Test
    void withoutRedisAllOperationsAreNoOps() {
        assertFalse(service.enabled());
        service.heartbeat("PT01");
        service.offline("PT01");
        service.rememberDevice("PT01", "d1", "fp", "win");
        service.lockRedscreen("PT01", 600);
        service.unlockRedscreen("PT01");

        assertEquals(-1L, service.onlineCount());
        assertEquals(0, service.deviceSnapshot("PT01").size());
        assertFalse(service.isRedscreenLocked("PT01"));
    }
}
