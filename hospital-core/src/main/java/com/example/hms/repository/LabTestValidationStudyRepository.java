package com.example.hms.repository;

import com.example.hms.model.LabTestValidationStudy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface LabTestValidationStudyRepository extends JpaRepository<LabTestValidationStudy, UUID> {

    List<LabTestValidationStudy> findByLabTestDefinition_IdOrderByStudyDateDesc(UUID definitionId);

    // ── Dashboard count queries ──────────────────────────────────────────────

    /** Total studies for all definitions belonging to a hospital. */
    long countByLabTestDefinition_Hospital_Id(UUID hospitalId);

    /** Passed studies for a hospital. */
    long countByPassedTrueAndLabTestDefinition_Hospital_Id(UUID hospitalId);

    /** Failed studies for a hospital. */
    long countByPassedFalseAndLabTestDefinition_Hospital_Id(UUID hospitalId);

    /** Studies recorded on or after a given date for a hospital (inclusive). */
    @Query("""
        SELECT COUNT(s) FROM LabTestValidationStudy s
        WHERE s.labTestDefinition.hospital.id = :hospitalId
          AND s.studyDate >= :since
    """)
    long countByHospitalAndStudyDateOnOrAfter(@Param("hospitalId") UUID hospitalId,
                                              @Param("since") LocalDate since);

    /**
     * Studies whose linked definition is currently in PENDING_DIRECTOR_APPROVAL state.
     * Used by the Lab Director dashboard to show studies awaiting sign-off.
     */
    @Query("""
        SELECT COUNT(s) FROM LabTestValidationStudy s
        WHERE s.labTestDefinition.hospital.id = :hospitalId
          AND s.labTestDefinition.approvalStatus = com.example.hms.enums.LabTestDefinitionApprovalStatus.PENDING_DIRECTOR_APPROVAL
    """)
    long countStudiesPendingDirectorApproval(@Param("hospitalId") UUID hospitalId);

    /**
     * Aggregated validation study stats per test definition for a hospital.
     * Returns: [testDefinitionId, testName, testCode, totalStudies, passedStudies, failedStudies, lastStudyDate]
     */
    @Query("""
        SELECT s.labTestDefinition.id,
               s.labTestDefinition.name,
               s.labTestDefinition.testCode,
               COUNT(s),
               SUM(CASE WHEN s.passed = true THEN 1 ELSE 0 END),
               SUM(CASE WHEN s.passed = false THEN 1 ELSE 0 END),
               MAX(s.studyDate)
          FROM LabTestValidationStudy s
         WHERE s.labTestDefinition.hospital.id = :hospitalId
         GROUP BY s.labTestDefinition.id, s.labTestDefinition.name, s.labTestDefinition.testCode
         ORDER BY MAX(s.studyDate) DESC
        """)
    List<Object[]> findValidationSummaryByHospitalId(@Param("hospitalId") UUID hospitalId);

    /**
     * Aggregated validation study stats across all hospitals (for SUPER_ADMIN).
     */
    @Query("""
        SELECT s.labTestDefinition.id,
               s.labTestDefinition.name,
               s.labTestDefinition.testCode,
               COUNT(s),
               SUM(CASE WHEN s.passed = true THEN 1 ELSE 0 END),
               SUM(CASE WHEN s.passed = false THEN 1 ELSE 0 END),
               MAX(s.studyDate)
          FROM LabTestValidationStudy s
         GROUP BY s.labTestDefinition.id, s.labTestDefinition.name, s.labTestDefinition.testCode
         ORDER BY MAX(s.studyDate) DESC
        """)
    List<Object[]> findValidationSummaryAll();
}

