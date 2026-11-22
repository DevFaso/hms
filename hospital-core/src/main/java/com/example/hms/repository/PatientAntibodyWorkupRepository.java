package com.example.hms.repository;

import com.example.hms.enums.AntibodyWorkupStatus;
import com.example.hms.model.PatientAntibodyWorkup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every read is by patient AND hospital: a work-up is the record of the hospital that made it. */
public interface PatientAntibodyWorkupRepository extends JpaRepository<PatientAntibodyWorkup, UUID> {

    List<PatientAntibodyWorkup> findByPatient_IdAndHospital_IdOrderByRecordedAtDesc(UUID patientId, UUID hospitalId);

    boolean existsByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    Optional<PatientAntibodyWorkup> findFirstByPatient_IdAndHospital_IdAndStatusOrderByRecordedAtDesc(
        UUID patientId, UUID hospitalId, AntibodyWorkupStatus status);

    boolean existsByPatient_IdAndHospital_IdAndStatusAndTransfusionRequestId(
        UUID patientId, UUID hospitalId, AntibodyWorkupStatus status, UUID transfusionRequestId);
}
