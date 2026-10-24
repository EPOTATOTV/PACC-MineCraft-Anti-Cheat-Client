package com.potatotv.pacc.repository;

import com.potatotv.pacc.domain.CrossEvidenceArchive;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CrossEvidenceArchiveRepository extends JpaRepository<CrossEvidenceArchive, String> {

    List<CrossEvidenceArchive> findByPteidOrderByCreatedAtDesc(String pteid);
}
