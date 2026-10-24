package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 跨端证据链归档快照（R-05）。
 *
 * <p>证据链本身是实时拼装出来的，事件表会随清理策略变化。归档把当时的完整 JSON 与
 * SHA-256 校验和固化下来，后续复查不再受原始数据留存影响。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_cross_evidence_archive")
public class CrossEvidenceArchive {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String pteid;

    /** 触发归档的管理员标识；自动化触发时可为空。 */
    @Column(length = 128)
    private String operator;

    @Lob
    @Column(nullable = false)
    private String snapshot;

    /** 快照 JSON 的 SHA-256（小写十六进制），用于核对归档内容是否被改动。 */
    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
