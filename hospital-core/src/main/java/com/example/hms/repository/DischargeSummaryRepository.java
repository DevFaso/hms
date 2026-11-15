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
     * QA D1760: {@code dischargeDate} is a day, so several visits closed the
     * same day came back in no order. Every list is newest first by date and
     * time, nulls (legacy rows with no time) after the timed ones of the day,
     * then by creation. DischargeSummaryOrderingGuardTest holds every list
     * query of this repository to it.
     */
    String NEWEST_FIRST = " ORDER BY ds.dischargeDate DESC, ds.dischargeTime DESC NULLS LAST, ds.createdAt DESC";


    /**
     * Find discharge summary by encounter
     */
    Optional<DischargeSummary> findByEncounter_Id(UUID encounterId);

    /**
     * Find all discharge summaries for a patient
     */
    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.patient.id = :patientId" + NEWEST_FIRST)
    List<DischargeSummary> findByPatient_IdOrderByDischargeDateDesc(@Param("patientId") UUID patientId);

    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.patient.id = :patientId AND ds.hospital.id = :hospitalId" + NEWEST_FIRST)
    List<DischargeSummary> findByPatient_IdAndHospital_IdOrderByDischargeDateDesc(@Param("patientId") UUID patientId, @Param("hospitalId") UUID hospitalId);

    /** E9 #59e — across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.patient.id = :patientId AND ds.hospital.id IN :hospitalIds" + NEWEST_FIRST)
    List<DischargeSummary> findByPatient_IdAndHospital_IdInOrderByDischargeDateDesc(@Param("patientId") UUID patientId, @Param("hospitalIds") java.util.Collection<UUID> hospitalIds);

    /**
     * FHIR read path: same rows, with the mapper-walked associations fetched
     * up front (the DocumentReference mapper dereferences all four).
     */
    @org.springframework.data.jpa.repository.EntityGraph(
        attributePaths = {"patient", "encounter", "hospital", "dischargingProvider"})
    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.patient.id = :patientId AND ds.hospital.id = :hospitalId" + NEWEST_FIRST)
    List<DischargeSummary> findWithAssociationsByPatient_IdAndHospital_IdOrderByDischargeDateDesc(
            @Param("patientId") UUID patientId, @Param("hospitalId") UUID hospitalId);

    /** E9 #60b — the readable-set sibling, for FHIR {@code $everything}. */
    @org.springframework.data.jpa.repository.EntityGraph(
        attributePaths = {"patient", "encounter", "hospital", "dischargingProvider"})
    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.patient.id = :patientId AND ds.hospital.id IN :hospitalIds" + NEWEST_FIRST)
    List<DischargeSummary> findWithAssociationsByPatient_IdAndHospital_IdInOrderByDischargeDateDesc(
            @Param("patientId") UUID patientId, @Param("hospitalIds") java.util.Collection<UUID> hospitalIds);

    /**
     * Find all discharge summaries for a hospital within a date range
     */
    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.hospital.id = :hospitalId " +
           "AND ds.dischargeDate BETWEEN :startDate AND :endDate" + NEWEST_FIRST)
    List<DischargeSummary> findByHospitalAndDateRange(
        @Param("hospitalId") UUID hospitalId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

    /**
     * Find unfinalized discharge summaries for a hospital
     */
    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.hospital.id = :hospitalId " +
           "AND ds.isFinalized = false" + NEWEST_FIRST)
    List<DischargeSummary> findUnfinalizedByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Find discharge summaries with pending test results
     */
    @Query("SELECT DISTINCT ds FROM DischargeSummary ds " +
           "JOIN ds.pendingTestResults ptr " +
           "WHERE ds.hospital.id = :hospitalId " +
           "AND SIZE(ds.pendingTestResults) > 0" + NEWEST_FIRST)
    List<DischargeSummary> findWithPendingTestResults(@Param("hospitalId") UUID hospitalId);

    /**
     * Find discharge summaries by discharging provider
     */
    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.dischargingProvider.id = :providerId" + NEWEST_FIRST)
    List<DischargeSummary> findByDischargingProvider_IdOrderByDischargeDateDesc(@Param("providerId") UUID providerId);

    @Query("SELECT ds FROM DischargeSummary ds WHERE ds.dischargingProvider.id = :providerId AND ds.hospital.id = :hospitalId" + NEWEST_FIRST)
    List<DischargeSummary> findByDischargingProvider_IdAndHospital_IdOrderByDischargeDateDesc(@Param("providerId") UUID providerId, @Param("hospitalId") UUID hospitalId);

    /**
     * Check if discharge summary exists for encounter
     */
    boolean existsByEncounter_Id(UUID encounterId);

    /**
     * Count discharge summaries for hospital on a specific date
     */
    long countByHospital_IdAndDischargeDate(UUID hospitalId, LocalDate dischargeDate);
}
