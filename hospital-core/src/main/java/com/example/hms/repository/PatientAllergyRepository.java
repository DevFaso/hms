package com.example.hms.repository;

import com.example.hms.model.PatientAllergy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientAllergyRepository extends JpaRepository<PatientAllergy, UUID> {

    List<PatientAllergy> findByPatient_Id(UUID patientId);

    List<PatientAllergy> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    Optional<PatientAllergy> findByIdAndPatient_IdAndHospital_Id(UUID id, UUID patientId, UUID hospitalId);

    /** E9 #56 — idempotency guard for the free-text import: has this patient any row from that source? */
    boolean existsByPatient_IdAndSourceSystem(UUID patientId, String sourceSystem);
}
