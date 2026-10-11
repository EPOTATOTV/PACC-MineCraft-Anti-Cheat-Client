package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.InspectSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface InspectSessionRepository extends JpaRepository<InspectSession, String> {

    List<InspectSession> findByStateOrderByStartedAtDesc(String state);

    /** 扫描已超过 expiresAt 的进行中会话，供超时自动断开。 */
    List<InspectSession> findByStateAndExpiresAtBefore(String state, Instant threshold);
}