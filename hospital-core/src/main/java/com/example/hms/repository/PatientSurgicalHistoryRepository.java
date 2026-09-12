package com.example.hms.repository;

import java.util.Collection;
import com.example.hms.model.PatientSurgicalHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PatientSurgicalHistoryRepository extends JpaRepository<PatientSurgicalHistory, UUID> {

    List<PatientSurgicalHistory> findByPatient_Id(UUID patientId);

    /** Acting hospital only — still read by the consent-sharing path until E9 #65. */
    List<PatientSurgicalHistory> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    /** E9 #59 — the readable set from {@code RecordAccessPolicy.readableHospitalIds}. */
    List<PatientSurgicalHistory> findByPatient_IdAndHospital_IdIn(UUID patientId, Collection<UUID> hospitalIds);
}
