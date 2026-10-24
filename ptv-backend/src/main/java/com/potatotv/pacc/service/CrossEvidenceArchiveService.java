package com.potatotv.pacc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.cluster.ClusterLock;
import com.potatotv.pacc.domain.CrossEvidenceArchive;
import com.potatotv.pacc.repository.CrossEvidenceArchiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 跨端证据链归档（R-05）。
 *
 * <p>同一 PTEID 的归档是幂等竞争：多实例同时点击只允许一个真正写库，其余直接返回“正在归档”。
 * 锁覆盖「拼装快照 + 写库」全过程，写库是独立事务，方法返回时已经提交，不存在锁先释放、
 * 事务后提交导致重复归档的窗口。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossEvidenceArchiveService {

    /** 归档锁 TTL；正常归档远小于该值，超时由 TTL 自动释放。 */
    private static final long LOCK_TTL_SECONDS = 30L;

    private final CrossEvidenceService crossEvidenceService;
    private final CrossEvidenceArchiveRepository archiveRepository;
    private final ClusterLock clusterLock;
    private final ObjectMapper mapper;

    public CrossEvidenceArchive archive(String pteid, String operator) {
        String key = pteid == null ? "" : pteid.trim();
        if (key.isEmpty() || key.length() > 64) {
            throw new IllegalArgumentException("PTEID 不合法");
        }
        ClusterLock.Lease lease = clusterLock.tryAcquire("evidence-archive:" + key, LOCK_TTL_SECONDS);
        if (lease == null) {
            throw new IllegalStateException("该玩家的证据链正在归档，请稍后重试");
        }
        try {
            CrossEvidenceChain chain = crossEvidenceService.chain(key);
            String snapshot = mapper.writeValueAsString(chain);
            CrossEvidenceArchive record = CrossEvidenceArchive.builder()
                    .id("cea_" + UUID.randomUUID().toString().replace("-", ""))
                    .pteid(key)
                    .operator(operator == null ? "" : operator.trim())
                    .snapshot(snapshot)
                    .sha256(sha256Hex(snapshot))
                    .createdAt(Instant.now())
                    .build();
            CrossEvidenceArchive saved = archiveRepository.save(record);
            log.info("证据链已归档 pteid={} id={} items={}", key, saved.getId(), chain.items().size());
            return saved;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("证据链序列化失败", e);
        } finally {
            lease.close();
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺失 SHA-256 实现", e);
        }
    }
}
