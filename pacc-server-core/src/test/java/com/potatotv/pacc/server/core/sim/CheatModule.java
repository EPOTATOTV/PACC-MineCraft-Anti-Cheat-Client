package com.potatotv.pacc.server.core.sim;

import java.util.Set;

/**
 * T3 作弊模块枚举。
 *
 * <p>{@code expectedCheatTypes} 为当前代码里「已有对应检测项」产出的 cheatType 集合；
 * 为空表示该模块在当前代码里**没有对应检测项**（未实现），不会被计入检出率断言。
 * 例如 phase / jesus 目前没有检测通道，属于未实现。
 */
enum CheatModule {

    SPEED(Set.of("SPEED")),
    FLY(Set.of("FLY")),
    REACH(Set.of("REACH")),
    AUTO_CLICKER(Set.of("AUTO_CLICKER")),
    KILL_AURA(Set.of("KILL_AURA")),
    // Timer 与 Blink 都由 PacketIntelCheck 的 packet-timing 检测器产出同一 cheatType
    TIMER(Set.of("packet-timing")),
    BLINK(Set.of("packet-timing")),
    NO_FALL(Set.of("NO_FALL")),
    PHASE(Set.of()),
    JESUS(Set.of()),
    SCAFFOLD(Set.of("SCAFFOLD")),
    FAST_EAT(Set.of("FAST_EAT")),
    AUTO_TOTEM(Set.of("AUTO_TOTEM"));

    private final Set<String> expectedCheatTypes;

    CheatModule(Set<String> expectedCheatTypes) {
        this.expectedCheatTypes = expectedCheatTypes;
    }

    /** 是否在当前代码里有对应检测项。 */
    boolean hasDetection() {
        return !expectedCheatTypes.isEmpty();
    }

    /** 当前检测项产出的 cheatType 集合（P-08 回归覆盖检查用）。 */
    Set<String> expectedCheatTypes() {
        return expectedCheatTypes;
    }

    /** 命中的 cheatType 是否属于本模块的对应检测项。 */
    boolean isCorrespondingHit(String cheatType) {
        return expectedCheatTypes.contains(cheatType);
    }
}
