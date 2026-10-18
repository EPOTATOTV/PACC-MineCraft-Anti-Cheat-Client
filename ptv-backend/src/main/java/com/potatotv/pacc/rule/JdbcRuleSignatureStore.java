package com.potatotv.pacc.rule;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * PRL 规则签名存储（P-09）。表结构由 {@code V43__prl_rule_signatures.sql} 建。
 *
 * <p>签名与版本一一对应，主键 {@code (rule_name, version)}；重复提交同一版本用覆盖写。
 */
@Repository
public class JdbcRuleSignatureStore {

    private static final RowMapper<SignatureRecord> MAPPER = (rs, rowNum) -> new SignatureRecord(
            rs.getString("rule_name"),
            rs.getString("version"),
            rs.getString("signature"),
            rs.getString("algorithm"),
            rs.getString("signed_by"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcRuleSignatureStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入或覆盖签名。 */
    public void save(String ruleName, String version, String signature, String algorithm, String signedBy) {
        int updated = jdbc.update("""
                UPDATE prl_rule_signatures
                   SET signature = ?, algorithm = ?, signed_by = ?, created_at = ?
                 WHERE rule_name = ? AND version = ?""",
                signature, algorithm, signedBy, Timestamp.from(Instant.now()), ruleName, version);
        if (updated > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO prl_rule_signatures
                       (rule_name, version, signature, algorithm, signed_by, created_at)
                VALUES (?,?,?,?,?,?)""",
                ruleName, version, signature, algorithm, signedBy, Timestamp.from(Instant.now()));
    }

    public Optional<SignatureRecord> find(String ruleName, String version) {
        return jdbc.query("""
                        SELECT rule_name, version, signature, algorithm, signed_by, created_at
                          FROM prl_rule_signatures
                         WHERE rule_name = ? AND version = ?""",
                MAPPER, ruleName, version).stream().findFirst();
    }

    /** 一条签名记录。 */
    public record SignatureRecord(
            String ruleName,
            String version,
            String signature,
            String algorithm,
            String signedBy,
            Instant createdAt) {
    }
}
