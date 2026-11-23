package com.example.hms.repository;

import com.example.hms.enums.AntibodyWorkupStatus;
import com.example.hms.model.PatientAntibodyWorkup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A work-up is written at the hospital that made it, and read for the PATIENT, wherever it was made (owner decision
 * 2026-11-22): an alloantibody is permanent and can become undetectable, so the hospital that transfuses next must
 * know of it. The reads below are by patient only, deliberately, and are not narrowed by the record-sharing
 * choice of the patient: {@code PositiveAntibodyScreenService} is their one reader, shows another hospital's row
 * as the fact, the antibody, the date and that hospital's name only, and accounts each such read. The one read by
 * hospital is the referral decision, which serves a request of that hospital.
 */
public interface PatientAntibodyWorkupRepository extends JpaRepository<PatientAntibodyWorkup, UUID> {

    List<PatientAntibodyWorkup> findByPatient_IdOrderByRecordedAtDesc(UUID patientId);

    boolean existsByPatient_Id(UUID patientId);

    Optional<PatientAntibodyWorkup> findFirstByPatient_IdAndStatusOrderByRecordedAtDesc(
        UUID patientId, AntibodyWorkupStatus status);

    boolean existsByPatient_IdAndHospital_IdAndStatusAndTransfusionRequestId(
        UUID patientId, UUID hospitalId, AntibodyWorkupStatus status, UUID transfusionRequestId);
}
