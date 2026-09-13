package com.example.hms.repository;

import com.example.hms.model.medication.PharmacyFill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.Collection;

@Repository
public interface PharmacyFillRepository extends JpaRepository<PharmacyFill, UUID> {

    /**
     * Find all pharmacy fills for a patient, ordered by fill date descending.
     */
    List<PharmacyFill> findByPatient_IdOrderByFillDateDesc(UUID patientId);

    /**
     * E9 #59c — fills across the readable hospitals
     * ({@code RecordAccessPolicy.readableHospitalIds}). Replaces the
     * single-hospital finder, which had no caller left.
     */
    List<PharmacyFill> findByPatient_IdAndHospital_IdInOrderByFillDateDesc(UUID patientId, Collection<UUID> hospitalIds);

    /**
     * E9 #59c — the date-ranged sibling. Replaces a JPQL query that had NO
     * hospital predicate at all: with a date range the medication timeline
     * read every tenant's fills, SCHEMA-isolated ones included.
     */
    List<PharmacyFill> findByPatient_IdAndHospital_IdInAndFillDateBetweenOrderByFillDateDesc(
        UUID patientId, Collection<UUID> hospitalIds, LocalDate startDate, LocalDate endDate);

    /**
     * Find pharmacy fills linked to a specific prescription.
     */
    List<PharmacyFill> findByPrescription_IdOrderByFillDateDesc(UUID prescriptionId);

    /**
     * Find pharmacy fills from a specific source system.
     */
    List<PharmacyFill> findBySourceSystemOrderByFillDateDesc(String sourceSystem);

    /**
     * Find pharmacy fills by external reference ID.
     */
    List<PharmacyFill> findByExternalReferenceId(String externalReferenceId);

    /**
     * Find all controlled substance fills for a patient.
     */
    @Query("SELECT pf FROM PharmacyFill pf WHERE pf.patient.id = :patientId " +
           "AND pf.controlledSubstance = true " +
           "ORDER BY pf.fillDate DESC")
    List<PharmacyFill> findControlledSubstanceFills(@Param("patientId") UUID patientId);

    /**
     * Find recent pharmacy fills for a patient (within last N days).
     */
    @Query("SELECT pf FROM PharmacyFill pf WHERE pf.patient.id = :patientId " +
           "AND pf.fillDate >= :sinceDate " +
           "ORDER BY pf.fillDate DESC")
    List<PharmacyFill> findRecentFills(
        @Param("patientId") UUID patientId,
        @Param("sinceDate") LocalDate sinceDate
    );

    /**
     * Count pharmacy fills for a patient.
     */
    long countByPatient_Id(UUID patientId);
}
