package com.potatotv.pacc.service;

import java.util.List;

/**
 * 同一 PTEID 的跨端证据链（P-06）：玩家端检测事件、设备指纹与服务端插件事件按时间合并。
 *
 * <p>设备指纹单独列出，不混进时间线：它是「同一台机器出现过哪些检测」的关联维度，
 * 时间线只放有明确发生时间的事件。
 *
 * @param pteid            查询的 PTEID
 * @param clientEventCount 玩家端事件条数（窗口内）
 * @param serverEventCount 服务端事件条数（窗口内）
 * @param devices          该 PTEID 的登录设备
 * @param hardwareFingerprints 该 PTEID 上报过的硬件指纹摘要
 * @param items            合并后的时间线，按发生时间倒序
 */
public record CrossEvidenceChain(
        String pteid,
        int clientEventCount,
        int serverEventCount,
        List<CrossEvidenceDevice> devices,
        List<CrossEvidenceHardware> hardwareFingerprints,
        List<CrossEvidenceItem> items) {
}
