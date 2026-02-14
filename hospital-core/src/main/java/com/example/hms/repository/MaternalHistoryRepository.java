package com.example.hms.repository;

import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Maternal History entity with comprehensive query methods.
 * Supports version tracking, temporal queries, and risk-based filtering.
 */
@SuppressWarnings("java:S107") // JPQL search methods require multiple filter parameters
@Repository
public interface MaternalHistoryRepository extends JpaRepository<MaternalHistory, UUID> {

    /**
     * Find all maternal history records for a specific patient, ordered by recorded date descending.
     */
    List<MaternalHistory> findByPatientOrderByRecordedDateDesc(Patient patient);

    /**
     * Find all maternal history records for a specific patient by patient ID.
     */
    @Query("SELECT mh FROM MaternalHistory mh WHERE mh.patient.id = :patientId ORDER BY mh.recordedDate DESC")
    List<MaternalHistory> findByPatientIdOrderByRecordedDateDesc(@Param("patientId") UUID patientId);

    /**
     * Find the most recent (current version) maternal history for a patient.
     */
  @Query("""
    SELECT mh FROM MaternalHistory mh
    WHERE mh.patient.id = :patientId
    ORDER BY mh.versionNumber DESC, mh.recordedDate DESC
    """)
    List<MaternalHistory> findByPatientIdOrderByVersionDesc(@Param("patientId") UUID patientId, Pageable pageable);
    
    default Optional<MaternalHistory> findCurrentByPatientId(UUID patientId) {
        List<MaternalHistory> results = findByPatientIdOrderByVersionDesc(patientId, Pageable.ofSize(1));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find a specific version of maternal history for a patient.
     */
    @Query("""
        SELECT mh FROM MaternalHistory mh 
        WHERE mh.patient.id = :patientId 
          AND mh.versionNumber = :versionNumber
        """)
    Optional<MaternalHistory> findByPatientIdAndVersion(
        @Param("patientId") UUID patientId,
        @Param("versionNumber") Integer versionNumber
    );

    /**
     * Find all versions of maternal history for a patient.
     */
    @Query("""
        SELECT mh FROM MaternalHistory mh 
        WHERE mh.patient.id = :patientId 
        ORDER BY mh.versionNumber DESC, mh.recordedDate DESC
        """)
    List<MaternalHistory> findAllVersionsByPatientId(@Param("patientId") UUID patientId);

    /**
     * Find maternal history records by hospital with pagination.
     */
    Page<MaternalHistory> findByHospitalOrderByRecordedDateDesc(Hospital hospital, Pageable pageable);

    /**
     * Find maternal history records by hospital ID with pagination.
     */
    @Query("SELECT mh FROM MaternalHistory mh WHERE mh.hospital.id = :hospitalId ORDER BY mh.recordedDate DESC")
    Page<MaternalHistory> findByHospitalIdOrderByRecordedDateDesc(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    /**
     * Find maternal history records within a date range for a patient.
     */
    @Query("""
        SELECT mh FROM MaternalHistory mh
        WHERE mh.patient.id = :patientId
          AND mh.recordedDate >= :startDate
          AND mh.recordedDate <= :endDate
        ORDER BY mh.recordedDate DESC
        """)
    List<MaternalHistory> findByPatientIdAndDateRange(
        @Param("patientId") UUID patientId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    /**
     * Search maternal history with comprehensive filters.
     */
    @Query("""
        SELECT mh FROM MaternalHistory mh
        WHERE (:hospitalId IS NULL OR mh.hospital.id = :hospitalId)
          AND (:patientId IS NULL OR mh.patient.id = :patientId)
          AND (:riskCategory IS NULL OR mh.riskCategory = :riskCategory)
          AND (:dataComplete IS NULL OR mh.dataComplete = :dataComplete)
          AND (:reviewedByProvider IS NULL OR mh.reviewedByProvider = :reviewedByProvider)
          AND (:dateFrom IS NULL OR mh.recordedDate >= :dateFrom)
          AND (:dateTo IS NULL OR mh.recordedDate <= :dateTo)
        ORDER BY mh.recordedDate DESC
        """)
    Page<MaternalHistory> searchMaternalHistory(
        @Param("hospitalId") UUID hospitalId,
        @Param("patientId") UUID patientId,
        @Param("riskCategory") String riskCategory,
        @Param("dataComplete") Boolean dataComplete,
        @Param("reviewedByProvider") Boolean reviewedByProvider,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        Pageable pageable
    );

    /**
     * Find high-risk maternal history records for a hospital.
     */
    @Query("""
        SELECT mh FROM MaternalHistory mh
        WHERE mh.hospital.id = :hospitalId
          AND (mh.riskCategory = 'HIGH' 
            OR mh.gestationalDiabetesHistory = true
            OR mh.preeclampsiaHistory = true
            OR mh.eclampsiaHistory = true
            OR mh.hellpSyndromeHistory = true
            OR mh.pretermLaborHistory = true
            OR mh.postpartumHemorrhageHistory = true
            OR mh.placentaPreviaHistory = true
            OR mh.placentalAbruptionHistory = true)
        ORDER BY mh.recordedDate DESC
        """)
    Page<MaternalHistory> findHighRiskByHospital(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    /**
     * Find maternal history records requiring provider review.
     */
    @Query("""
        SELECT mh FROM MaternalHistory mh
        WHERE mh.hospital.id = :hospitalId
          AND mh.dataComplete = true
          AND (mh.reviewedByProvider IS NULL OR mh.reviewedByProvider = false)
        ORDER BY mh.recordedDate DESC
        """)
    Page<MaternalHistory> findPendingReviewByHospital(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    /**
     * Find maternal history records requiring specialist referral.
     */
    @Query("""
        SELECT mh FROM MaternalHistory mh
        WHERE mh.hospital.id = :hospitalId
          AND mh.requiresSpecialistReferral = true
        ORDER BY mh.recordedDate DESC
        """)
    Page<MaternalHistory> findRequiringSpecialistReferral(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    /**
     * Find maternal history records with psychosocial concerns.
     */
    @Query("""
        SELECT mh FROM MaternalHistory mh
        WHERE mh.hospital.id = :hospitalId
          AND (mh.domesticViolenceConcerns = true
            OR mh.anxietyPresent = true
            OR mh.adequateHousing = false
            OR mh.foodSecurity = false
            OR mh.financialConcerns = true)
        ORDER BY mh.recordedDate DESC
        """)
    Page<MaternalHistory> findWithPsychosocialConcerns(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    /**
     * Count maternal history records for a patient.
     */
    long countByPatient(Patient patient);

    /**
     * Count the highest version number for a patient (to support version incrementing).
     */
    @Query("""
        SELECT COALESCE(MAX(mh.versionNumber), 0) 
        FROM MaternalHistory mh 
        WHERE mh.patient.id = :patientId
        """)
    Integer findMaxVersionByPatientId(@Param("patientId") UUID patientId);

    /**
     * Count high-risk maternal history records for a hospital.
     */
    @Query("""
        SELECT COUNT(mh) FROM MaternalHistory mh
        WHERE mh.hospital.id = :hospitalId
          AND (mh.riskCategory = 'HIGH' 
            OR mh.gestationalDiabetesHistory = true
            OR mh.preeclampsiaHistory = true
            OR mh.eclampsiaHistory = true
            OR mh.hellpSyndromeHistory = true
            OR mh.pretermLaborHistory = true
            OR mh.postpartumHemorrhageHistory = true
            OR mh.placentaPreviaHistory = true
            OR mh.placentalAbruptionHistory = true)
        """)
    long countHighRiskByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Count maternal history records pending provider review.
     */
    @Query("""
        SELECT COUNT(mh) FROM MaternalHistory mh
        WHERE mh.hospital.id = :hospitalId
          AND mh.dataComplete = true
          AND (mh.reviewedByProvider IS NULL OR mh.reviewedByProvider = false)
        """)
    long countPendingReviewByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Check if a patient has any maternal history records.
     */
    boolean existsByPatient_Id(UUID patientId);

    /**
     * Find incomplete maternal history records for follow-up.
     */
    @Query("""
        SELECT mh FROM MaternalHistory mh
        WHERE mh.hospital.id = :hospitalId
          AND mh.dataComplete = false
        ORDER BY mh.recordedDate DESC
        """)
    Page<MaternalHistory> findIncompleteByHospital(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    /**
     * Find maternal history records for patients with specific chronic conditions.
     */
    @Query("""
        SELECT mh FROM MaternalHistory mh
        WHERE mh.hospital.id = :hospitalId
          AND (mh.diabetes = true
            OR mh.hypertension = true
            OR mh.thyroidDisorder = true
            OR mh.cardiacDisease = true
            OR mh.renalDisease = true
            OR mh.autoimmuneDisorder = true)
        ORDER BY mh.recordedDate DESC
        """)
    Page<MaternalHistory> findWithChronicConditions(@Param("hospitalId") UUID hospitalId, Pageable pageable);
}
