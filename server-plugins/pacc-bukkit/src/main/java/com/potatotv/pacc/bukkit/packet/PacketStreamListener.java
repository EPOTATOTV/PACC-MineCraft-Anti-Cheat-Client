package com.potatotv.pacc.bukkit.packet;

import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.packet.PacketTypes;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据包监听：在 Netty 线程上把客户端发来的包解析成归一化的 {@link PacketView}，交给
 * {@link PacketPump} 转到主线程。
 *
 * <p>只解析检测真正用得到的包：移动四态（位置 / 位置+朝向 / 朝向 / 仅落地）、挖掘、放置、
 * 攻击与交互、挥手、Pong 事务回执、传送确认。目的很明确——Timer/Blink 要包到达时序，
 * NO_FALL 要真实的 onGround，战斗检测要攻击与挥手的相对时序，X-Ray 要挖掘方块坐标。
 * 别的包一律不看，免得为无关数据付出解析开销。
 *
 * <p>解析失败（畸形包、字段越界）只计数并跳过：一个坏包不该让整条检测链路异常，也不该
 * 被当成合法包继续喂给检测项。
 */
final class PacketStreamListener implements PacketListener {

    private final PacketPump pump;
    private final AtomicLong parseFailures = new AtomicLong();

    PacketStreamListener(PacketPump pump) {
        this.pump = pump;
    }

    /** 解析失败的包数（畸形包），用于判断客户端是否在发脏包。 */
    long parseFailures() {
        return parseFailures.get();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null) {
            return;
        }
        String playerId = resolvePlayerId(user);
        if (playerId == null) {
            return;
        }
        PacketView view;
        try {
            view = map(event, playerId, System.currentTimeMillis());
        } catch (RuntimeException ex) {
            parseFailures.incrementAndGet();
            return;
        }
        if (view != null) {
            pump.offer(view);
        }
    }

    private static String resolvePlayerId(User user) {
        return user.getUUID() != null ? user.getUUID().toString() : user.getName();
    }

    private static PacketView map(PacketReceiveEvent event, String playerId, long nowMs) {
        if (PacketType.Play.Client.PLAYER_POSITION.equals(event.getPacketType())) {
            return movement(event, playerId, nowMs, PacketTypes.PLAYER_POSITION);
        }
        if (PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION.equals(event.getPacketType())) {
            return movement(event, playerId, nowMs, PacketTypes.PLAYER_POSITION_AND_ROTATION);
        }
        if (PacketType.Play.Client.PLAYER_ROTATION.equals(event.getPacketType())) {
            return movement(event, playerId, nowMs, PacketTypes.PLAYER_ROTATION);
        }
        if (PacketType.Play.Client.PLAYER_FLYING.equals(event.getPacketType())) {
            return movement(event, playerId, nowMs, PacketTypes.PLAYER_FLYING);
        }
        if (PacketType.Play.Client.PLAYER_DIGGING.equals(event.getPacketType())) {
            return digging(event, playerId, nowMs);
        }
        if (PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT.equals(event.getPacketType())) {
            return blockPlacement(event, playerId, nowMs);
        }
        if (PacketType.Play.Client.INTERACT_ENTITY.equals(event.getPacketType())) {
            return interactEntity(event, playerId, nowMs);
        }
        if (PacketType.Play.Client.ANIMATION.equals(event.getPacketType())) {
            Map<String, String> fields = new LinkedHashMap<>(2);
            fields.put(PacketTypes.FIELD_ACTION,
                    new WrapperPlayClientAnimation(event).getHand().name());
            return new PacketView(playerId, PacketTypes.ANIMATION, nowMs, fields);
        }
        if (PacketType.Play.Client.PONG.equals(event.getPacketType())) {
            Map<String, String> fields = new LinkedHashMap<>(2);
            fields.put(PacketTypes.FIELD_TRANSACTION_ID,
                    Integer.toString(new WrapperPlayClientPong(event).getId()));
            return new PacketView(playerId, PacketTypes.PONG, nowMs, fields);
        }
        if (PacketType.Play.Client.TELEPORT_CONFIRM.equals(event.getPacketType())) {
            Map<String, String> fields = new LinkedHashMap<>(2);
            fields.put(PacketTypes.FIELD_TRANSACTION_ID,
                    Integer.toString(new WrapperPlayClientTeleportConfirm(event).getTeleportId()));
            return new PacketView(playerId, PacketTypes.TELEPORT_CONFIRM, nowMs, fields);
        }
        return null;
    }

    /**
     * 移动四态。每个包只构造一个 wrapper——wrapper 的读操作会推进自己的缓冲游标，同一事件上
     * 反复构造不同 wrapper 是没必要的风险，位置与朝向都从这一个实例上取。
     */
    private static PacketView movement(PacketReceiveEvent event, String playerId, long nowMs, String packetType) {
        Map<String, String> fields = new LinkedHashMap<>(7);
        boolean onGround;
        if (PacketTypes.PLAYER_POSITION.equals(packetType)) {
            WrapperPlayClientPlayerPosition wrapper = new WrapperPlayClientPlayerPosition(event);
            putPosition(fields, wrapper.getPosition());
            onGround = wrapper.isOnGround();
        } else if (PacketTypes.PLAYER_POSITION_AND_ROTATION.equals(packetType)) {
            WrapperPlayClientPlayerPositionAndRotation wrapper =
                    new WrapperPlayClientPlayerPositionAndRotation(event);
            putPosition(fields, wrapper.getPosition());
            putRotation(fields, wrapper.getYaw(), wrapper.getPitch());
            onGround = wrapper.isOnGround();
        } else if (PacketTypes.PLAYER_ROTATION.equals(packetType)) {
            WrapperPlayClientPlayerRotation wrapper = new WrapperPlayClientPlayerRotation(event);
            putRotation(fields, wrapper.getYaw(), wrapper.getPitch());
            onGround = wrapper.isOnGround();
        } else {
            onGround = new WrapperPlayClientPlayerFlying(event).isOnGround();
        }
        fields.put(PacketTypes.FIELD_ON_GROUND, Boolean.toString(onGround));
        return new PacketView(playerId, packetType, nowMs, fields);
    }

    private static void putPosition(Map<String, String> fields, Vector3d position) {
        fields.put(PacketTypes.FIELD_X, Double.toString(position.getX()));
        fields.put(PacketTypes.FIELD_Y, Double.toString(position.getY()));
        fields.put(PacketTypes.FIELD_Z, Double.toString(position.getZ()));
    }

    private static void putRotation(Map<String, String> fields, float yaw, float pitch) {
        fields.put(PacketTypes.FIELD_YAW, Float.toString(yaw));
        fields.put(PacketTypes.FIELD_PITCH, Float.toString(pitch));
    }

    private static PacketView digging(PacketReceiveEvent event, String playerId, long nowMs) {
        WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
        Vector3i block = digging.getBlockPosition();
        Map<String, String> fields = new LinkedHashMap<>(6);
        fields.put(PacketTypes.FIELD_ACTION, digging.getAction().name());
        fields.put(PacketTypes.FIELD_BLOCK_X, Integer.toString(block.getX()));
        fields.put(PacketTypes.FIELD_BLOCK_Y, Integer.toString(block.getY()));
        fields.put(PacketTypes.FIELD_BLOCK_Z, Integer.toString(block.getZ()));
        fields.put(PacketTypes.FIELD_SEQUENCE, Integer.toString(digging.getSequence()));
        return new PacketView(playerId, PacketTypes.PLAYER_DIGGING, nowMs, fields);
    }

    private static PacketView blockPlacement(PacketReceiveEvent event, String playerId, long nowMs) {
        WrapperPlayClientPlayerBlockPlacement placement = new WrapperPlayClientPlayerBlockPlacement(event);
        Vector3i block = placement.getBlockPosition();
        Map<String, String> fields = new LinkedHashMap<>(5);
        fields.put(PacketTypes.FIELD_BLOCK_X, Integer.toString(block.getX()));
        fields.put(PacketTypes.FIELD_BLOCK_Y, Integer.toString(block.getY()));
        fields.put(PacketTypes.FIELD_BLOCK_Z, Integer.toString(block.getZ()));
        fields.put(PacketTypes.FIELD_SEQUENCE, Integer.toString(placement.getSequence()));
        return new PacketView(playerId, PacketTypes.PLAYER_BLOCK_PLACEMENT, nowMs, fields);
    }

    private static PacketView interactEntity(PacketReceiveEvent event, String playerId, long nowMs) {
        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
        Map<String, String> fields = new LinkedHashMap<>(3);
        fields.put(PacketTypes.FIELD_ACTION, interact.getAction().name());
        fields.put(PacketTypes.FIELD_TARGET_ID, Integer.toString(interact.getEntityId()));
        return new PacketView(playerId, PacketTypes.INTERACT_ENTITY, nowMs, fields);
    }
}
