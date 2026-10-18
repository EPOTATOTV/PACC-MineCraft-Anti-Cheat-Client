-- =====================================================================
-- V43: P-09 规则签名（规则市场）
-- 规则版本表 V40 只有源码 SHA-256，没有来源真实性证明。这里单独存作者签名，
-- 不修改 V40 已合入的表结构：签名是可选附加信息，缺签名时沿用原有校验和流程。
--
-- 签名口径：SHA256withRSA / SHA256withECDSA / Ed25519，签名内容为
--   rule_name + "\n" + version + "\n" + source
-- 公钥由 pacc.rules.signature-public-key（Base64 X.509 DER）注入。
-- =====================================================================

CREATE TABLE IF NOT EXISTS prl_rule_signatures (
    rule_name   VARCHAR(128) NOT NULL COMMENT '规则名',
    version     VARCHAR(64)  NOT NULL COMMENT '规则版本',
    signature   VARCHAR(1024) NOT NULL COMMENT 'Base64 签名',
    algorithm   VARCHAR(32)  NOT NULL COMMENT 'SHA256withRSA / SHA256withECDSA / Ed25519',
    signed_by   VARCHAR(128) NULL     COMMENT '签名人标识',
    created_at  DATETIME(6)  NOT NULL COMMENT '入库时间',
    PRIMARY KEY (rule_name, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
