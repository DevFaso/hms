package com.example.hms.repository;

import com.example.hms.model.PatientBloodGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientBloodGroupRepository extends JpaRepository<PatientBloodGroup, UUID> {

    /**
     * The one current type and screen for a patient at a hospital.
     *
     * <p>Backed by the partial unique index {@code uq_blood_group_current}, so
     * "current" is a database guarantee rather than a convention this query
     * hopes holds.
     */
    Optional<PatientBloodGroup> findByPatient_IdAndHospital_IdAndSupersededFalse(UUID patientId, UUID hospitalId);

    /** Full history, newest first — a superseded screen stays readable. */
    List<PatientBloodGroup> findByPatient_IdAndHospital_IdOrderByPerformedAtDesc(UUID patientId, UUID hospitalId);

    /**
     * QA D3351, owner decision 2026-11-22: the patient's screens with one result at EVERY hospital, superseded
     * ones included, the newest first. Read with {@code POSITIVE} by {@code PositiveAntibodyScreenService} only:
     * the allo-immunisation flag follows the patient (patient-safety data, as an allergy is), whatever the
     * patient chose about record sharing. Nothing else of another hospital's blood group is shown from it.
     */
    List<PatientBloodGroup> findByPatient_IdAndAntibodyScreenOrderByPerformedAtDesc(
        UUID patientId, com.example.hms.enums.AntibodyScreenResult antibodyScreen);

    /**
     * QA D3901: the current grouping at one hospital with its hospital loaded, for the patient record's « groupe
     * confirmé » (the record is mapped outside the blood bank, where the lazy hospital may not be reachable).
     */
    @Query("SELECT g FROM PatientBloodGroup g JOIN FETCH g.hospital WHERE g.patient.id = :patientId "
        + "AND g.hospital.id = :hospitalId AND g.superseded = false")
    Optional<PatientBloodGroup> findCurrentWithHospital(@Param("patientId") UUID patientId,
                                                        @Param("hospitalId") UUID hospitalId);

    /**
     * QA D3901: the patient's current grouping at every hospital, the newest first, for the patient's OWN record
     * (patient portal), which belongs to no single hospital.
     */
    @Query("SELECT g FROM PatientBloodGroup g JOIN FETCH g.hospital WHERE g.patient.id = :patientId "
        + "AND g.superseded = false ORDER BY g.performedAt DESC")
    List<PatientBloodGroup> findCurrentAtEveryHospital(@Param("patientId") UUID patientId);

    @Query("SELECT g FROM PatientBloodGroup g WHERE g.hospital.id = :hospitalId AND g.superseded = false "
        + "ORDER BY g.performedAt DESC")
    List<PatientBloodGroup> findCurrentByHospital(@Param("hospitalId") UUID hospitalId);
}
