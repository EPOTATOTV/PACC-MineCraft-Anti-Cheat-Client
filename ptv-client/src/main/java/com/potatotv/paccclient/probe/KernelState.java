package com.potatotv.paccclient.probe;

/**
 * 内核级回调检测结果（文档 §4.4）。
 *
 * <p><b>数据来源</b>：Windows 上由 {@link KernelBridge} 经 PaccPtv 内核驱动
 * （{@code platform/kernel-windows/}）取回，无驱动通道或响应非法时返回 {@link #unsupported}，
 * {@link #supported()} 为 false，全部计数保持 0，绝不伪造。</p>
 *
 * <p><b>能力边界</b>：当前驱动只暴露 SSDT 完整性与已加载驱动枚举两个查询，故
 * {@link #ssdtHooks()}（SSDT 分歧条目数）与 {@link #unsignedDriverCount()}（未签名驱动数）
 * 有真实值；IDT hook 与内核回调枚举、内核态调试标志驱动未提供 IOCTL，{@link #idtHooks()}
 * 与 {@link #suspiciousCallbackCount()} 恒 0，{@link #note()} 会显式标注，避免把「查不了」
 * 当成「没有」（文档 §9 注意事项 2）。</p>
 *
 * @param supported                 是否有内核级能力支撑（驱动桥接可用则为 true）
 * @param ssdtHooks                 SSDT 分歧条目数（内核 hook 痕迹）
 * @param idtHooks                  IDT hook 数（当前驱动未暴露，恒 0）
 * @param unsignedDriverCount       未签名驱动数
 * @param suspiciousCallbackCount   可疑内核回调数（当前驱动未暴露，恒 0）
 * @param note                      能力说明（可为 null）
 */
public record KernelState(boolean supported, int ssdtHooks, int idtHooks,
                          int unsignedDriverCount, int suspiciousCallbackCount, String note) {

    /** 无内核能力：全部计数为 0 并附原因。 */
    public static KernelState unsupported(String note) {
        return new KernelState(false, 0, 0, 0, 0, note);
    }
}