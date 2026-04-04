package com.example.hms.repository;

import com.example.hms.model.LabQcEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LabQcEventRepository extends JpaRepository<LabQcEvent, UUID> {

    Page<LabQcEvent> findByHospitalId(UUID hospitalId, Pageable pageable);

    Page<LabQcEvent> findByTestDefinitionId(UUID testDefinitionId, Pageable pageable);

    Page<LabQcEvent> findByTestDefinitionIdAndHospitalId(UUID testDefinitionId, UUID hospitalId, Pageable pageable);

    /**
     * Aggregated QC stats per test definition for a given hospital.
     * Returns: [testDefinitionId, testName, totalEvents, passedEvents, failedEvents, lastEventDate]
     */
    @Query("""
        SELECT e.testDefinition.id,
               e.testDefinition.name,
               COUNT(e),
               SUM(CASE WHEN e.passed = true THEN 1 ELSE 0 END),
               SUM(CASE WHEN e.passed = false THEN 1 ELSE 0 END),
               MAX(e.recordedAt)
          FROM LabQcEvent e
         WHERE e.hospitalId = :hospitalId
           AND e.testDefinition IS NOT NULL
         GROUP BY e.testDefinition.id, e.testDefinition.name
         ORDER BY MAX(e.recordedAt) DESC
        """)
    List<Object[]> findQcSummaryByHospitalId(@Param("hospitalId") UUID hospitalId);

    /**
     * Aggregated QC stats across all hospitals (for SUPER_ADMIN).
     */
    @Query("""
        SELECT e.testDefinition.id,
               e.testDefinition.name,
               COUNT(e),
               SUM(CASE WHEN e.passed = true THEN 1 ELSE 0 END),
               SUM(CASE WHEN e.passed = false THEN 1 ELSE 0 END),
               MAX(e.recordedAt)
          FROM LabQcEvent e
         WHERE e.testDefinition IS NOT NULL
         GROUP BY e.testDefinition.id, e.testDefinition.name
         ORDER BY MAX(e.recordedAt) DESC
        """)
    List<Object[]> findQcSummaryAll();
}
