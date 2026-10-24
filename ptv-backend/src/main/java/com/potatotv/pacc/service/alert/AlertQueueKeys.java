package com.potatotv.pacc.service.alert;

/** 告警队列相关的 Redis 键。集中放置，避免生产与消费两端拼错前缀。 */
public final class AlertQueueKeys {

    /** 待投递队列（LPUSH 入、BRPOP 出）。 */
    public static final String QUEUE = "pacc:alert:notify";

    /** 超过重试上限的队列项 ID。 */
    public static final String DEAD = "pacc:alert:notify:dead";

    /** 投递去重键：同一队列项在多实例下只允许一个实例发送。 */
    public static final String DEDUPE_PREFIX = "pacc:alert:notify:dedupe:";

    private AlertQueueKeys() {
    }

    public static String dedupe(String queueId) {
        return DEDUPE_PREFIX + queueId;
    }
}
