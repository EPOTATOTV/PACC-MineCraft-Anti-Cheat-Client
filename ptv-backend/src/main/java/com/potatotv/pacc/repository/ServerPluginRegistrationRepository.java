package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.ServerPluginRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServerPluginRegistrationRepository extends JpaRepository<ServerPluginRegistration, String> {

    List<ServerPluginRegistration> findByServerTypeOrderByLastSeenAtDesc(String serverType);
}
