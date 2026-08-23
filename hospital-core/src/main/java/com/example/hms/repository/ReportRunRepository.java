package com.example.hms.repository;

import com.example.hms.model.platform.ReportRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportRunRepository extends JpaRepository<ReportRun, UUID> {

    Optional<ReportRun> findByDefinition_IdAndPeriodToken(UUID definitionId, String periodToken);

    List<ReportRun> findTop50ByDefinition_IdOrderByCreatedAtDesc(UUID definitionId);
}
