package com.potatotv.paccclient.probe;

/**
 * 内核级回调检测结果（文档 §4.4）。
 *
 * <p><b>数据来源</b>：Windows 上由 {@link KernelBridge} 经 PaccPtv 内核驱动
 * （{@code platform/kernel-windows/}）取回，无驱动通道或响应非法时返回 {@link #unsupported}，
 * {@link #supported()} 为 false，全部计数保持 0，绝不伪造。</p>
 *
 * <p><b>能力边界</b>：SSDT 完整性、已加载驱动枚举、IDT 完整性、内核回调枚举四项均由驱动暴露、
 * 由 {@link KernelBridge} 解析，因此 {@link #ssdtHooks()}、{@link #idtHooks()}、
 * {@link #unsignedDriverCount()}、{@link #suspiciousCallbackCount()} 在驱动可用时都是真实值。
 * 老版本驱动缺少 IDT / 回调枚举 IOCTL（{@code +14} / {@code +15}），或驱动自述读取失败时，
 * 对应计数保持 0 并在 {@link #note()} 里标注「不可用」，避免把「查不了」当成「没有」
 * （文档 §9 注意事项 2）。</p>
 *
 * @param supported                 是否有内核级能力支撑（驱动桥接可用则为 true）
 * @param ssdtHooks                 SSDT 分歧条目数（内核 hook 痕迹）
 * @param idtHooks                  IDT 异常条目数（不可用时保持 0，由 note 标注）
 * @param unsignedDriverCount       未签名驱动数
 * @param suspiciousCallbackCount   可疑内核回调数（不可用时保持 0，由 note 标注）
 * @param note                      能力说明（可为 null）
 */
public record KernelState(boolean supported, int ssdtHooks, int idtHooks,
                          int unsignedDriverCount, int suspiciousCallbackCount, String note) {

    /** 无内核能力：全部计数为 0 并附原因。 */
    public static KernelState unsupported(String note) {
        return new KernelState(false, 0, 0, 0, 0, note);
    }
}