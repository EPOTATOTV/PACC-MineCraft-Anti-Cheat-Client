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

    public CheatCatalog(SignatureLibrary library) {
        this.fingerprints = library.fingerprints();
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
