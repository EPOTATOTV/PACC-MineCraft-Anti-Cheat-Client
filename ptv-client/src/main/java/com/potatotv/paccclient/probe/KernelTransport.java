package com.potatotv.paccclient.probe;

/**
 * 内核设备 IOCTL 传输接缝（{@code platform/kernel-windows/pacc_common.h} 协议的用户态一侧）。
 *
 * <p>把「与 PaccPtv 内核设备通信」收敛成单一方法，使 {@link KernelBridge} 的响应解析可以脱离
 * 真实驱动离线测试：生产实现在 Windows 侧经原生层转发 {@code DeviceIoControl}，测试用固化
 * 字节流的假实现。</p>
 *
 * <p><b>契约</b>：{@code ioctl} 为 {@code CTL_CODE(...)} 生成的 32 位控制码；{@code request}
 * 为 METHOD_BUFFERED 输入缓冲（无输入时传空数组）；返回值为驱动写入输出缓冲的原始字节，
 * 且必须恰好是驱动在 {@code IoStatus.Information} 中报告的字节数。设备不存在 / 权限不足 /
 * 状态非成功 / 传输失败时返回 {@code null}——调用方据此优雅降级，绝不伪造内核数据。</p>
 */
public interface KernelTransport {

    /**
     * 向内核设备发起一次 IOCTL 往返。
     *
     * @param ioctl   {@code CTL_CODE} 生成的 32 位控制码
     * @param request METHOD_BUFFERED 输入字节（无输入传空数组）
     * @return 输出缓冲原始字节；设备不可用时 {@code null}
     */
    byte[] query(int ioctl, byte[] request);
}
