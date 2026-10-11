package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据一致性检查：对同一键比较客户端自报值与服务端观测值，相对偏差超过 15% 记为 DATA_MISMATCH。
 */
public final class DataConsistencyCheck implements ServerCheck {

    private static final double DEVIATION_THRESHOLD = 0.15;

    @Override
    public CheckType type() {
        return CheckType.DATA_CONSISTENCY;
    }

    @Override
    public List<DetectionResult> onDataConsistency(
            String playerId, Map<String, String> clientData, Map<String, String> serverData) {
        if (clientData == null || clientData.isEmpty() || serverData == null || serverData.isEmpty()) {
            return List.of();
        }
        List<DetectionResult> results = new ArrayList<>(1);
        for (Map.Entry<String, String> entry : clientData.entrySet()) {
            String key = entry.getKey();
            if (!serverData.containsKey(key)) {
                continue;
            }
            Double client = parse(entry.getValue());
            Double server = parse(serverData.get(key));
            if (client == null || server == null) {
                continue;
            }
            double base = Math.max(Math.abs(server), 1e-9);
            double deviation = Math.abs(client - server) / base;
            if (deviation > DEVIATION_THRESHOLD) {
                results.add(DetectionResult.violation(CheckType.DATA_CONSISTENCY, "DATA_MISMATCH", 75,
                        String.format("字段 %s 客户端 %s 与服务端 %s 偏差 %.0f%%", key, client, server, deviation * 100),
                        System.currentTimeMillis()));
            }
        }
        return results;
    }

    private static Double parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
