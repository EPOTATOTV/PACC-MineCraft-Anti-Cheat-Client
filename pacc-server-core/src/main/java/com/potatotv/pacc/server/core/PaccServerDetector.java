package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.PunishmentType;

import java.util.List;

/**
 * 对外检测门面（设计文档 §4.1）。宿主只需面对这几个方法；引擎可选择性实现它。
 */
public interface PaccServerDetector {

    PlayerPosition getPlayerPosition(String playerId);

    GameMode getGameMode(String playerId);

    String getClientBrand(String playerId);

    void alert(String playerId, String message);

    void punish(String playerId, PunishmentType type, String reason);

    List<DetectionResult> detectMove(String playerId, Position from, Position to);

    List<DetectionResult> detectCombat(String playerId, CombatEvent event);

    List<DetectionResult> detectPacket(String playerId, PacketView packet);
}
