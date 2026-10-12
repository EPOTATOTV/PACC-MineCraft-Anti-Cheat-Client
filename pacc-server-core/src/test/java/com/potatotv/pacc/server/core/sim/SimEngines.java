package com.potatotv.pacc.server.core.sim;

import com.potatotv.pacc.server.core.check.AddonCheck;
import com.potatotv.pacc.server.core.check.ClientBrandCheck;
import com.potatotv.pacc.server.core.check.CombatCheck;
import com.potatotv.pacc.server.core.check.DataConsistencyCheck;
import com.potatotv.pacc.server.core.check.InteractionCheck;
import com.potatotv.pacc.server.core.check.InventoryCheck;
import com.potatotv.pacc.server.core.check.MoveCheck;
import com.potatotv.pacc.server.core.check.PacketCheck;
import com.potatotv.pacc.server.core.check.ServerCheck;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.intel.IntelChecks;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import com.potatotv.pacc.server.core.report.NoopPtvServerReporter;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟用引擎装配：8 个基础检查 + {@link IntelChecks#defaults()} 四类 intel 检查，
 * 装配口径与 {@code IntelChecksTest} 一致，默认仅告警。
 */
final class SimEngines {

    private SimEngines() {
    }

    static DetectionEngine fullEngine(ServerPluginConfig config, SimServerAdapter adapter) {
        List<ServerCheck> checks = new ArrayList<>();
        checks.add(new MoveCheck(config));
        checks.add(new CombatCheck(config));
        checks.add(new PacketCheck());
        checks.add(new InteractionCheck(config));
        checks.add(new InventoryCheck());
        checks.add(new ClientBrandCheck(config));
        checks.add(new AddonCheck(config));
        checks.add(new DataConsistencyCheck());
        checks.addAll(IntelChecks.defaults());
        return new DetectionEngine(config, adapter, checks, NoopPtvServerReporter.INSTANCE);
    }
}
