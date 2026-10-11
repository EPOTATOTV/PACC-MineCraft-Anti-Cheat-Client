package com.potatotv.pacc.bukkit;

import java.nio.charset.StandardCharsets;

/**
 * 与平台无关的纯计算工具：视角夹角与客户端品牌解码。
 * 抽出来是为了能在没有 Bukkit 服务端的情况下单测。
 */
public final class DetectMath {

    private DetectMath() {
    }

    /**
     * 攻击者朝向与指向目标方向之间的夹角（度，范围 [0,180]，0 表示正对目标）。
     *
     * @param yawDeg   攻击者偏航角（度），Minecraft 约定 0=+Z、90=-X
     * @param pitchDeg 攻击者俯仰角（度），正值朝下
     * @param dx       从攻击者到目标的方向向量分量
     * @param dy       从攻击者到目标的方向向量分量
     * @param dz       从攻击者到目标的方向向量分量
     */
    public static double viewAngleDeg(double yawDeg, double pitchDeg, double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) {
            return 0.0;
        }
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double fx = -Math.sin(yaw) * Math.cos(pitch);
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * Math.cos(pitch);
        double dot = fx * (dx / len) + fy * (dy / len) + fz * (dz / len);
        // 浮点误差可能让点积略微越界，钳一下避免 acos 返回 NaN。
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    /**
     * 解码客户端品牌通道（{@code minecraft:brand}）载荷。
     * 常规格式是 VarInt 长度前缀 + UTF-8 字符串；畸形或越界一律返回空串，绝不抛异常。
     */
    public static String decodeBrand(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        int offset = 0;
        int length = 0;
        int shift = 0;
        while (offset < data.length) {
            byte b = data[offset++];
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 21) {
                return "";
            }
        }
        if (length <= 0 || offset + length > data.length) {
            return "";
        }
        return new String(data, offset, length, StandardCharsets.UTF_8);
    }
}
