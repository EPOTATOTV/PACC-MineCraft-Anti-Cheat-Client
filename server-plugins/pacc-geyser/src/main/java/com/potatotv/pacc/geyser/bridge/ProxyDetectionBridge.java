package com.potatotv.pacc.geyser.bridge;

import com.potatotv.pacc.server.core.model.DetectionResult;

import java.util.function.Consumer;

/**
 * 与服务端 Paper/Nukkit 插件之间的 Plugin Messaging 联动通道（预留给"代理检测 + 服务端确认"）。
 *
 * <p>Geyser 处在基岩客户端与 Java 服务端之间，本身只能看到 Bedrock 侧信息；部分检测结论需要在
 * 后端服务端插件里二次确认。这里预留一个方向清晰的接口：Geyser 扩展发布检测线索，服务端插件回传确认结果。
 * 具体的 Plugin Messaging 传输（通道注册、包编解码）尚未接入——Geyser 的公开扩展 API 目前不暴露
 * 代理到后端的自定义插件通道，故实现先落成占位，等待上游 API 或改走其它通道时再补齐。
 *
 * <p>约束：该通道只用于检测联动，不承载跨服封禁指令。
 */
public interface ProxyDetectionBridge {

    /** 发布一条检测线索给后端服务端插件。 */
    void publishDetectionHint(String playerId, DetectionResult result);

    /** 注册服务端插件确认结果的处理回调。 */
    void onConfirmation(Consumer<DetectionConfirmation> handler);

    /** 一次服务端确认。{@code confirmed} 为 true 表示服务端侧也判定为异常。 */
    record DetectionConfirmation(String playerId, String cheatType, boolean confirmed) {
    }

    /** 尚未接入传输层时的占位实现：只记日志。 */
    final class Noop implements ProxyDetectionBridge {

        private final Consumer<String> logger;

        public Noop(Consumer<String> logger) {
            this.logger = logger;
        }

        @Override
        public void publishDetectionHint(String playerId, DetectionResult result) {
            // 传输层未接入，暂不发送。
        }

        @Override
        public void onConfirmation(Consumer<DetectionConfirmation> handler) {
            if (logger != null) {
                logger.accept("Plugin Messaging 联动通道尚未接入，服务端确认回调暂不生效");
            }
        }
    }
}
