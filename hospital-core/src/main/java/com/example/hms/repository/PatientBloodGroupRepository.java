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

    @Query("SELECT g FROM PatientBloodGroup g WHERE g.hospital.id = :hospitalId AND g.superseded = false "
        + "ORDER BY g.performedAt DESC")
    List<PatientBloodGroup> findCurrentByHospital(@Param("hospitalId") UUID hospitalId);
}
