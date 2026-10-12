package com.potatotv.pacc.detection.threatintel;

import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.SignatureLibrary;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指纹目录视图测试：M1 覆盖度口径、筛选、空库行为。
 */
class CheatCatalogTest {

    @Test
    void 内置库目录覆盖全部类别() {
        SignatureLibrary library = SignatureLibrary.builtin();
        CheatCatalog catalog = new CheatCatalog(library);
        assertEquals(library.size(), catalog.total());
        assertTrue(catalog.total() >= 100, "目录快照应与内置库一致且不少于 100 条");
        assertTrue(catalog.coversAllCategories());
        catalog.coverage().forEach((category, count) -> assertTrue(count > 0, "类别缺条目: " + category));
        assertEquals(catalog.total(),
                catalog.coverage().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void 按类别与风险等级筛选() {
        CheatCatalog catalog = new CheatCatalog(SignatureLibrary.builtin());

        List<CheatFingerprint> drivers = catalog.byCategory(FingerprintCategory.DRIVER);
        assertFalse(drivers.isEmpty());
        assertTrue(drivers.stream().allMatch(fp -> fp.category() == FingerprintCategory.DRIVER));

        List<CheatFingerprint> critical = catalog.byRiskLevel(5);
        assertFalse(critical.isEmpty());
        assertTrue(critical.stream().allMatch(fp -> fp.riskLevel() >= 5));

        assertEquals(catalog.total(), catalog.byRiskLevel(1).size());
    }

    @Test
    void 空库目录为空视图() {
        CheatCatalog catalog = new CheatCatalog(SignatureLibrary.of(List.of()));
        assertEquals(0, catalog.total());
        assertFalse(catalog.coversAllCategories());
        assertTrue(catalog.byCategory(FingerprintCategory.CLIENT).isEmpty());
        assertTrue(catalog.byRiskLevel(1).isEmpty());
        catalog.coverage().forEach((category, count) -> assertEquals(0, count));
    }
}
