package com.potatotv.pacc.addon;

import java.nio.file.Path;
import java.util.Set;

/**
 * 单次扫描得到的 Addon 描述。玩家端只读本机文件，这里不包含任何处置动作。
 *
 * @param uuid           清单 header.uuid，可能为空（清单缺字段时）
 * @param name           清单 header.name，可能为空
 * @param version        清单 header.version，数组形式会拼成 {@code 1.0.0}
 * @param packKind       行为包或资源包
 * @param path           包目录绝对路径
 * @param sha256         manifest.json 的 SHA-256（十六进制小写），计算失败为 null
 * @param moduleTypes    清单 modules 里出现过的 type 集合
 * @param scriptKeywords 脚本里命中的作弊关键词集合
 * @param components     命中的可疑组件 / 资源包启发式标记集合
 * @param suspicious     是否被判定为可疑
 * @param knownCheat     是否命中已知作弊 UUID 或 SHA-256 特征
 */
public record ScannedAddon(
        String uuid,
        String name,
        String version,
        PackKind packKind,
        Path path,
        String sha256,
        Set<String> moduleTypes,
        Set<String> scriptKeywords,
        Set<String> components,
        boolean suspicious,
        boolean knownCheat) {
}
