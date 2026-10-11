package com.potatotv.pacc.geyser.packet;

import com.potatotv.pacc.server.core.model.PacketView;

import java.util.function.Consumer;

/**
 * 基岩版 / Java 版数据包处理器的注册入口（预留给包级检测）。
 *
 * <p>写这份接口时按需求预留了 {@code registerBedrockPacketHandler} / {@code registerJavaPacketHandler} 两个入口。
 * 但实际核对 Geyser 公开扩展 API（2.11.x）后确认：它只暴露事件总线与连接查询，没有面向扩展的数据包处理器注册接口，
 * 因此当前实现为占位，注册动作只记日志并返回 false；包级检测暂由事件回调承担。
 * 一旦上游放出对应 API，或改用其它通道，只需替换实现。
 */
public interface PacketHandlerRegistrar {

    /** 数据包处理回调。 */
    @FunctionalInterface
    interface PacketHandler {
        void onPacket(PacketView packet);
    }

    /** 注册基岩版上行数据包处理器。返回是否注册成功。 */
    boolean registerBedrockPacketHandler(PacketHandler handler);

    /** 注册 Java 版数据包处理器。返回是否注册成功。 */
    boolean registerJavaPacketHandler(PacketHandler handler);

    /** 当前 Geyser API 不支持包处理器注册时的占位实现。 */
    final class Unsupported implements PacketHandlerRegistrar {

        private final Consumer<String> logger;

        public Unsupported(Consumer<String> logger) {
            this.logger = logger;
        }

        @Override
        public boolean registerBedrockPacketHandler(PacketHandler handler) {
            return report();
        }

        @Override
        public boolean registerJavaPacketHandler(PacketHandler handler) {
            return report();
        }

        private boolean report() {
            if (logger != null) {
                logger.accept("当前 Geyser API 未提供数据包处理器注册接口，包级检测暂不生效");
            }
            return false;
        }
    }
}
