package com.potatotv.pacc.bedrock.listener;

import com.potatotv.pacc.bedrock.BedrockDetectMath;
import com.potatotv.pacc.bedrock.NukkitPlatformAdapter;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.Position;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.level.Location;

/**
 * 战斗事件监听：仅处理“玩家攻击实体”，构造 {@link CombatEvent} 交给引擎。
 */
public final class NukkitCombatListener implements Listener {

    private final DetectionEngine engine;

    public NukkitCombatListener(DetectionEngine engine) {
        this.engine = engine;
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        Entity target = event.getEntity();
        if (target == null) {
            return;
        }
        Location attackerLoc = attacker.getLocation();
        Location targetLoc = target.getLocation();

        // 攻击距离由内核从两点距离算出，此处用眼睛位置使 REACH 更贴近命中盒判定。
        double ax = attackerLoc.getX();
        double ay = attackerLoc.getY() + attacker.getEyeHeight();
        double az = attackerLoc.getZ();
        double tx = targetLoc.getX();
        double ty = targetLoc.getY() + target.getEyeHeight();
        double tz = targetLoc.getZ();
        Position attackerPos = new Position(ax, ay, az);
        Position targetPos = new Position(tx, ty, tz);

        double viewAngle = BedrockDetectMath.viewAngleDeg(
                attackerLoc.getYaw(), attackerLoc.getPitch(), tx - ax, ty - ay, tz - az);
        boolean critical = attacker.fallDistance > 0.0F;

        CombatEvent combatEvent = new CombatEvent(
                NukkitPlatformAdapter.playerId(attacker),
                targetId(target),
                attackerPos,
                targetPos,
                System.currentTimeMillis(),
                critical,
                viewAngle);
        engine.handleCombat(combatEvent);
    }

    private static String targetId(Entity target) {
        if (target instanceof Player targetPlayer) {
            return NukkitPlatformAdapter.playerId(targetPlayer);
        }
        return Long.toString(target.getId());
    }
}
