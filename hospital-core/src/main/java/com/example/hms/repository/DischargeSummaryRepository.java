package com.example.hms.repository;

import com.example.hms.model.discharge.DischargeSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for DischargeSummary entities
 * Part of Story #14: Discharge Summary Assembly
 */
@Repository
public interface DischargeSummaryRepository extends JpaRepository<DischargeSummary, UUID> {

    /**
     * Find discharge summary by encounter
     */
    Optional<DischargeSummary> findByEncounter_Id(UUID encounterId);

    /**
     * Find all discharge summaries for a patient
     */
    List<DischargeSummary> findByPatient_IdOrderByDischargeDateDesc(UUID patientId);

    /**
     * Find all discharge summaries for a hospital within a date range
     */
    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.hospital.id = :hospitalId " +
           "AND ds.dischargeDate BETWEEN :startDate AND :endDate " +
           "ORDER BY ds.dischargeDate DESC")
    List<DischargeSummary> findByHospitalAndDateRange(
        @Param("hospitalId") UUID hospitalId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    /**
     * Find unfinalized discharge summaries for a hospital
     */
    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.hospital.id = :hospitalId " +
           "AND ds.isFinalized = false " +
           "ORDER BY ds.dischargeDate DESC")
    List<DischargeSummary> findUnfinalizedByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Find discharge summaries with pending test results
     */
    @Query("SELECT DISTINCT ds FROM DischargeSummary ds " +
           "JOIN ds.pendingTestResults ptr " +
           "WHERE ds.hospital.id = :hospitalId " +
           "AND SIZE(ds.pendingTestResults) > 0 " +
           "ORDER BY ds.dischargeDate DESC")
    List<DischargeSummary> findWithPendingTestResults(@Param("hospitalId") UUID hospitalId);

    /**
     * Find discharge summaries by discharging provider
     */
    List<DischargeSummary> findByDischargingProvider_IdOrderByDischargeDateDesc(UUID providerId);

    /**
     * Check if discharge summary exists for encounter
     */
    boolean existsByEncounter_Id(UUID encounterId);

    /**
     * Count discharge summaries for hospital on a specific date
     */
    long countByHospital_IdAndDischargeDate(UUID hospitalId, LocalDate dischargeDate);
}
