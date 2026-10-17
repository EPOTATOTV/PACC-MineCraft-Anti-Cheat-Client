package com.potatotv.pacc.server.core.packet;

/**
 * 事务发包接缝：由平台适配层实现，负责真正往客户端发一个带 id 的事务包并返回该 id。
 *
 * <p>为什么要有这层：延迟补偿与 Disabler 判定都以「服务端确实发过事务、客户端按 id 回 ack」
 * 为前提。如果检测侧自己编造 id 而不真正发包，ack 永远对不上，ack 率恒为 0，会把所有正常
 * 玩家判成 Disabler。所以这里只负责发包，返回 {@code < 0} 表示平台发不出去，调用方应当
 * 记为「未发出」并放弃该轮延迟估计，而不是拿编造的 id 记账。
 */
public interface TransactionSender {

    /**
     * 向玩家发送一个事务包。
     *
     * @return 实际发到线上的事务 id；平台不支持或玩家不在线时返回 {@code -1}
     */
    int send(String playerId);
}
