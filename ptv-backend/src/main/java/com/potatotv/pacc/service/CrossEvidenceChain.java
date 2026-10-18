package com.potatotv.pacc.service;

import java.util.List;

/**
 * 同一 PTEID 的跨端证据链（P-06）：玩家端检测事件与服务端插件事件按时间合并。
 *
 * @param pteid            查询的 PTEID
 * @param clientEventCount 玩家端事件条数（窗口内）
 * @param serverEventCount 服务端事件条数（窗口内）
 * @param items            合并后的时间线，按发生时间倒序
 */
public record CrossEvidenceChain(
        String pteid,
        int clientEventCount,
        int serverEventCount,
        List<CrossEvidenceItem> items) {
}
