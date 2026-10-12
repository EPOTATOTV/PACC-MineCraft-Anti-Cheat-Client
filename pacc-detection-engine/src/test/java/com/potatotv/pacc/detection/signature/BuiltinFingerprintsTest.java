package com.potatotv.pacc.detection.signature;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置指纹库数据测试（技术文档 §十一 M1 里程碑验收项）。
 */
class BuiltinFingerprintsTest {

    private static final List<CheatFingerprint> ALL = BuiltinFingerprints.all();

    @Test
    void 条目总数不低于一百() {
        assertTrue(ALL.size() >= 100, "M1 里程碑要求 ≥100 条，实际 " + ALL.size());
    }

    @Test
    void 五大类别全覆盖() {
        Map<FingerprintCategory, Integer> counts = new EnumMap<>(FingerprintCategory.class);
        for (CheatFingerprint fp : ALL) {
            counts.merge(fp.category(), 1, Integer::sum);
        }
        for (FingerprintCategory category : FingerprintCategory.values()) {
            assertTrue(counts.getOrDefault(category, 0) > 0, "类别缺条目: " + category);
        }
    }

    @Test
    void id无重复() {
        Set<String> ids = new HashSet<>();
        for (CheatFingerprint fp : ALL) {
            assertFalse(ids.contains(fp.id()), "重复 id: " + fp.id());
            ids.add(fp.id());
        }
    }

    @Test
    void riskLevel都在一到五之间() {
        for (CheatFingerprint fp : ALL) {
            assertTrue(fp.riskLevel() >= 1 && fp.riskLevel() <= 5, "riskLevel 越界: " + fp.id());
        }
    }

    @Test
    void config条目必须带标记串() {
        // 既有契约：matchConfig 只读 windowTitles，CONFIG 条目的标记串必须放这里。
        for (CheatFingerprint fp : ALL) {
            if (fp.type() == FingerprintType.CONFIG) {
                assertFalse(fp.windowTitles().isEmpty(), "CONFIG 条目缺少标记串: " + fp.id());
            }
        }
    }

    @Test
    void 内存特征码必须可编译() {
        for (CheatFingerprint fp : ALL) {
            for (String pattern : fp.memoryPatterns()) {
                assertNotNull(MemoryPatternMatcher.compile(pattern), "非法特征码: " + fp.id() + " → " + pattern);
            }
        }
    }

    @Test
    void 特征列表与名称不为空白() {
        for (CheatFingerprint fp : ALL) {
            assertFalse(fp.name().isBlank(), "空白名称: " + fp.id());
        }
    }
}
