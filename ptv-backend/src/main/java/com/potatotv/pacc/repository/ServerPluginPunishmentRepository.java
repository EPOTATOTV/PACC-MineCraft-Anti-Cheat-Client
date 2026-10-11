package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ServerPluginPunishment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServerPluginPunishmentRepository extends JpaRepository<ServerPluginPunishment, String> {

    List<ServerPluginPunishment> findTop50ByServerIdOrderByReceivedAtDesc(String serverId);
}
