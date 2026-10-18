package com.potatotv.pacc.detection.threatintel;

import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.SignatureLibrary;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 指纹目录视图（技术文档 §4.2 threatintel 包 / §十一 M1 验收）。
 *
 * <p>构造时对签名库做一次快照：目录服务于覆盖度验收与运营查看，不随运行时的
 * 社区注册漂移；需要新视图就重新构造。
 */
public final class CheatCatalog {

    private final List<CheatFingerprint> fingerprints;
    private final String packVersion;
    private final String packChecksum;

    public CheatCatalog(SignatureLibrary library) {
        this(library, null);
    }

    /**
     * 带指纹包元数据的目录。{@code pack} 为 {@code null} 表示内置指纹，没有版本概念。
     */
    public CheatCatalog(SignatureLibrary library, SignaturePack pack) {
        this.fingerprints = library.fingerprints();
        this.packVersion = pack == null ? "" : pack.version();
        this.packChecksum = pack == null ? "" : pack.checksum();
    }

    /** 指纹包版本；内置指纹返回空串。 */
    public String packVersion() {
        return packVersion;
    }

    /** 指纹包校验和；内置指纹返回空串。 */
    public String packChecksum() {
        return packChecksum;
    }

    /** 全部指纹 id（按写入顺序）。 */
    public List<String> fingerprintIds() {
        return fingerprints.stream().map(CheatFingerprint::id).toList();
    }

    /** 是否存在重复 id——指纹包入库前的硬性校验项。 */
    public boolean hasDuplicateIds() {
        return fingerprintIds().stream().distinct().count() != fingerprints.size();
    }

    /** 按类别筛选。 */
    public List<CheatFingerprint> byCategory(FingerprintCategory category) {
        return fingerprints.stream().filter(fp -> fp.category() == category).toList();
    }

    /** 风险等级 ≥ min 的指纹。 */
    public List<CheatFingerprint> byRiskLevel(int min) {
        return fingerprints.stream().filter(fp -> fp.riskLevel() >= min).toList();
    }

    /** 各类别条数（M1 验收口径：五大类别全部非零）。 */
    public Map<FingerprintCategory, Integer> coverage() {
        Map<FingerprintCategory, Integer> counts = new EnumMap<>(FingerprintCategory.class);
        for (FingerprintCategory category : FingerprintCategory.values()) {
            counts.put(category, 0);
        }
        for (CheatFingerprint fp : fingerprints) {
            counts.merge(fp.category(), 1, Integer::sum);
        }
        return counts;
    }

    /** 指纹总数。 */
    public int total() {
        return fingerprints.size();
    }

    /** 是否五大类别都有条目。 */
    public boolean coversAllCategories() {
        return coverage().values().stream().allMatch(count -> count > 0);
    }
}
