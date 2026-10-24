package com.potatotv.pacc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.cluster.ClusterLock;
import com.potatotv.pacc.cluster.RedisClient;
import com.potatotv.pacc.domain.CrossEvidenceArchive;
import com.potatotv.pacc.repository.CrossEvidenceArchiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R-05：归档快照与同 PTEID 并发归档互斥。 */
class CrossEvidenceArchiveServiceTest {

    private CrossEvidenceService chainService;
    private CrossEvidenceArchiveRepository repository;
    private ClusterLock clusterLock;
    private CrossEvidenceArchiveService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chainService = mock(CrossEvidenceService.class);
        repository = mock(CrossEvidenceArchiveRepository.class);
        ObjectProvider<RedisClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        clusterLock = new ClusterLock(provider);
        service = new CrossEvidenceArchiveService(chainService, repository, clusterLock,
                new ObjectMapper().findAndRegisterModules());
        when(chainService.chain("PT01")).thenReturn(new CrossEvidenceChain(
                "PT01", 1, 0, List.of(), List.of(), List.of()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void archiveStoresSnapshotAndChecksum() {
        CrossEvidenceArchive saved = service.archive("PT01", "operator-a");

        assertNotNull(saved.getId());
        assertEquals("PT01", saved.getPteid());
        assertEquals("operator-a", saved.getOperator());
        assertEquals(64, saved.getSha256().length());
        assertTrue(saved.getSnapshot().contains("\"pteid\":\"PT01\""));
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void concurrentArchiveForSamePteidIsRejected() {
        try (ClusterLock.Lease ignored = clusterLock.tryAcquire("evidence-archive:PT01", 30)) {
            assertNotNull(ignored);
            assertThrows(IllegalStateException.class, () -> service.archive("PT01", "operator-b"));
        }
    }

    @Test
    void invalidPteidIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.archive(" ", "operator"));
        assertThrows(IllegalArgumentException.class, () -> service.archive("x".repeat(65), "operator"));
    }
}
