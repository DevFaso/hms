package com.example.hms.repository;

import com.example.hms.model.platform.ReportDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportDefinitionRepository extends JpaRepository<ReportDefinition, UUID> {

    /** The sweep's selection. Unscoped — system actor. */
    List<ReportDefinition> findByActiveTrue();

    List<ReportDefinition> findByHospital_IdOrderByCreatedAtDesc(UUID hospitalId);

    /** Scoped single-row load — the 404-not-403 tenancy idiom. */
    Optional<ReportDefinition> findByIdAndHospital_Id(UUID id, UUID hospitalId);
}
