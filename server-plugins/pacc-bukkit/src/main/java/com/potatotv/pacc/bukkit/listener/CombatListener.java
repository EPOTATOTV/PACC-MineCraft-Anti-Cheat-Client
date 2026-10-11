package com.potatotv.pacc.bukkit.listener;

import com.potatotv.pacc.bukkit.DetectMath;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.Position;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * 战斗事件监听：仅处理“玩家攻击实体”，构造 {@link CombatEvent} 交给引擎。
 */
public final class CombatListener implements Listener {

    private final DetectionEngine engine;

    public CombatListener(DetectionEngine engine) {
        this.engine = engine;
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        Location attackerEye = attacker.getEyeLocation();
        Location targetEye = target.getEyeLocation();
        Position attackerPos = new Position(attackerEye.getX(), attackerEye.getY(), attackerEye.getZ());
        Position targetPos = new Position(targetEye.getX(), targetEye.getY(), targetEye.getZ());

        // 攻击距离由内核从两点距离算出，此处用眼睛位置使 REACH 更贴近命中盒判定。
        double dx = targetEye.getX() - attackerEye.getX();
        double dy = targetEye.getY() - attackerEye.getY();
        double dz = targetEye.getZ() - attackerEye.getZ();
        double viewAngle = DetectMath.viewAngleDeg(attackerEye.getYaw(), attackerEye.getPitch(), dx, dy, dz);
        boolean critical = attacker.getFallDistance() > 0.0F;

        CombatEvent combatEvent = new CombatEvent(
                attacker.getUniqueId().toString(),
                target.getUniqueId().toString(),
                attackerPos,
                targetPos,
                System.currentTimeMillis(),
                critical,
                viewAngle);
        engine.handleCombat(combatEvent);
    }
}
