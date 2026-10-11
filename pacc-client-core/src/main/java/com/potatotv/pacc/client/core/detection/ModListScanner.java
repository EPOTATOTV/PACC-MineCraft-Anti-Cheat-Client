package com.potatotv.pacc.client.core.detection;

import com.potatotv.pacc.client.core.model.ClientCheckType;
import com.potatotv.pacc.client.core.model.ClientDetectionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 已知作弊模组 / 作弊客户端签名扫描。
 *
 * <p>匹配规则：模组 id、名称或文件名统一转小写后做子串匹配。签名库覆盖常见作弊客户端
 * （Wurst / Impact / Meteor / Future / RusherHack 等）及作弊 Mod 关键词。签名是明文常量，
 * 不随发行构建被外部配置覆盖，改签名库需要改代码。
 */
public final class ModListScanner {

    /** 命中时的作弊分类。 */
    public static final String CHEAT_TYPE = "CHEAT_CLIENT";

    /** 已知作弊客户端 / 作弊 Mod 签名，全部为小写。子串匹配，因此不必写全名。 */
    private static final List<String> CHEAT_SIGNATURES = List.of(
            "wurst",
            "impact",
            "meteor",
            "future",
            "rusherhack",
            "sigma",
            "inertia",
            "liquidbounce",
            "bleachhack",
            "salhack",
            "konas",
            "astolfo",
            "phobos",
            "aristois",
            "lambda",
            "xray",
            "x-ray",
            "baritone",
            "schematica",
            "vape",
            "kami",
            "ares",
            "wwe",
            "juul",
            "novoline",
            "rise");

    public List<ClientDetectionResult> scan(List<String> modIdentifiers) {
        return scan(modIdentifiers, System.currentTimeMillis());
    }

    /** 带时间戳的重载，便于测试与统一时间线。 */
    public List<ClientDetectionResult> scan(List<String> modIdentifiers, long timestampMs) {
        if (modIdentifiers == null || modIdentifiers.isEmpty()) {
            return List.of();
        }
        List<ClientDetectionResult> results = new ArrayList<>();
        for (String identifier : modIdentifiers) {
            String matched = match(identifier);
            if (matched != null) {
                results.add(ClientDetectionResult.violation(
                        ClientCheckType.MOD_LIST,
                        CHEAT_TYPE,
                        95,
                        "检测到已知作弊模组: " + identifier + "（命中签名 " + matched + "）",
                        timestampMs));
            }
        }
        return results;
    }

    /** 单个标识是否命中签名库。 */
    public boolean isKnownCheat(String identifier) {
        return match(identifier) != null;
    }

    /** 命中的签名，未命中返回 {@code null}。 */
    public String match(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        String normalized = identifier.toLowerCase(Locale.ROOT);
        for (String signature : CHEAT_SIGNATURES) {
            if (normalized.contains(signature)) {
                return signature;
            }
        }
        return null;
    }

    /** 内置签名的只读副本，供上层展示或自检。 */
    public List<String> signatures() {
        return CHEAT_SIGNATURES;
    }
}
