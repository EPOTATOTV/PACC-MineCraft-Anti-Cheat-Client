package com.potatotv.pacc.server.core.sim;

/**
 * T2 合法玩家模拟器的场景枚举，覆盖《检测代码完成度审查报告》要求的移动行为：
 * 走 / 跑 / 疾跑 / 跳跃 / 潜行 / 游泳 / 鞘翅滑翔 / 乘船 / 搭桥 / 高延迟，另有创造模式豁免场景。
 */
enum SimScenario {

    WALK,
    RUN,
    SPRINT,
    JUMP,
    SNEAK,
    SWIM,
    ELYTRA,
    BOAT,
    BRIDGE,
    HIGH_LATENCY,
    CREATIVE
}
