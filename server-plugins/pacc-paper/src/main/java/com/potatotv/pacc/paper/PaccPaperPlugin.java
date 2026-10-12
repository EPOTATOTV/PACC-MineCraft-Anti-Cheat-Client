package com.potatotv.pacc.paper;

import com.potatotv.pacc.bukkit.PaccBukkitPlugin;

/**
 * PACC 的 Paper 插件主类。
 *
 * <p>检测装配、事件监听与 PTV 上报全部复用 {@link PaccBukkitPlugin}；本类只作为 pacc-paper 产物的
 * 入口（plugin.yml 的 main 指向它），使下载到的 pacc-paper.jar 有独立主类而非隐式复用 Bukkit 层。
 * 平台差异仍由 {@code BukkitPlatformAdapter#detectServerType()} 在启动时判定。
 */
public final class PaccPaperPlugin extends PaccBukkitPlugin {
}
