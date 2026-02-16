package com.example.hms.repository;

import com.example.hms.model.medication.PharmacyFill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface PharmacyFillRepository extends JpaRepository<PharmacyFill, UUID> {

    /**
     * Find all pharmacy fills for a patient, ordered by fill date descending.
     */
    List<PharmacyFill> findByPatient_IdOrderByFillDateDesc(UUID patientId);

    /**
     * Find pharmacy fills for a patient in a specific hospital.
     */
    List<PharmacyFill> findByPatient_IdAndHospital_IdOrderByFillDateDesc(UUID patientId, UUID hospitalId);

    /**
     * Find pharmacy fills for a patient within a date range.
     */
    @Query("SELECT pf FROM PharmacyFill pf WHERE pf.patient.id = :patientId " +
           "AND pf.fillDate BETWEEN :startDate AND :endDate " +
           "ORDER BY pf.fillDate DESC")
    List<PharmacyFill> findByPatientAndDateRange(
        @Param("patientId") UUID patientId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );

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
