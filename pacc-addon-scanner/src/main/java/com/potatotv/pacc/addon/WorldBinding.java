package com.potatotv.pacc.addon;

import java.nio.file.Path;
import java.util.Set;

/**
 * 某个世界当前启用的 Addon 绑定。
 *
 * @param worldName          世界目录名
 * @param behaviorPackUuids  world_behavior_packs.json 里声明的 pack_id 集合
 * @param resourcePackUuids  world_resource_packs.json 里声明的 pack_id 集合
 */
public record WorldBinding(
        String worldName,
        Path path,
        Set<String> behaviorPackUuids,
        Set<String> resourcePackUuids) {
}
