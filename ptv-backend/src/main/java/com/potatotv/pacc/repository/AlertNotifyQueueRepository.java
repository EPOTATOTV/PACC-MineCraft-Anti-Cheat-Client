package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.alert.AlertNotifyQueue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AlertNotifyQueueRepository extends JpaRepository<AlertNotifyQueue, String> {

    Page<AlertNotifyQueue> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AlertNotifyQueue> findByStatusOrderByScheduledAtAsc(String status);

    /** P-07 调度器：取已到发送时间的待发队列。 */
    List<AlertNotifyQueue> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            String status, Instant scheduledAt);
}