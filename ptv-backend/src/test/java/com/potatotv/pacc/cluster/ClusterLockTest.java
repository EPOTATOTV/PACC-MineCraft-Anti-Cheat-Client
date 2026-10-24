package com.potatotv.pacc.cluster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R-05：无 Redis 时的进程内互斥语义。 */
class ClusterLockTest {

    private ClusterLock clusterLock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<RedisClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        clusterLock = new ClusterLock(provider);
    }

    @Test
    void secondAcquireFailsUntilLeaseReleased() {
        ClusterLock.Lease first = clusterLock.tryAcquire("evidence-archive:PT01", 30);
        assertNotNull(first);
        assertNull(clusterLock.tryAcquire("evidence-archive:PT01", 30));

        first.close();
        ClusterLock.Lease second = clusterLock.tryAcquire("evidence-archive:PT01", 30);
        assertNotNull(second);
        second.close();
    }

    @Test
    void withLockThrowsWhenOccupied() {
        try (ClusterLock.Lease ignored = clusterLock.tryAcquire("rule-release", 30)) {
            assertNotNull(ignored);
            assertThrows(IllegalStateException.class,
                    () -> clusterLock.withLock("rule-release", 30, 0, () -> "never"));
        }
    }

    @Test
    void closeIsIdempotent() {
        ClusterLock.Lease lease = clusterLock.tryAcquire("k", 30);
        assertNotNull(lease);
        lease.close();
        lease.close();
        assertNotNull(clusterLock.tryAcquire("k", 30));
    }
}
