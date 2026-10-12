package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.local.os.OsProbe;
import com.potatotv.pacc.detection.signature.SignatureLibrary;

/**
 * 本地扫描上下文：一次玩家端扫描的全部输入。
 *
 * @param playerId       玩家标识
 * @param gamePid        游戏进程 PID（&le;0 表示游戏未运行，只做环境面扫描）
 * @param gameDir        游戏安装目录（可为空）
 * @param probe          OS 探针
 * @param library        指纹库（内置 + 社区更新）
 * @param gameRunning    游戏是否正在运行（CE 类工具只在游戏运行时判定，避免误伤用户自用）
 */
public record LocalScanContext(
        String playerId,
        long gamePid,
        String gameDir,
        OsProbe probe,
        SignatureLibrary library,
        boolean gameRunning) {
}
