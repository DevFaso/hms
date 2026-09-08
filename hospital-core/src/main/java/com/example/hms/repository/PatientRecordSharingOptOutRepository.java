package com.example.hms.repository;

import com.example.hms.model.PatientRecordSharingOptOut;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientRecordSharingOptOutRepository extends JpaRepository<PatientRecordSharingOptOut, UUID> {

    /** The opt-out currently in force for this patient, if any. */
    Optional<PatientRecordSharingOptOut> findFirstByPatient_IdAndRevokedAtIsNullOrderByOptedOutAtDesc(UUID patientId);

    boolean existsByPatient_IdAndRevokedAtIsNull(UUID patientId);

    /** Full history, newest first — for the disclosure report. */
    List<PatientRecordSharingOptOut> findByPatient_IdOrderByOptedOutAtDesc(UUID patientId);
}
