package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ServerPluginEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServerPluginEventRepository extends JpaRepository<ServerPluginEvent, String> {

    List<ServerPluginEvent> findTop50ByServerIdOrderByReceivedAtDesc(String serverId);

    long countByRedscreenTriggeredTrue();
}
