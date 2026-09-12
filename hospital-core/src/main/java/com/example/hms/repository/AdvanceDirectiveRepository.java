package com.example.hms.repository;

import java.util.Collection;
import com.example.hms.model.AdvanceDirective;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AdvanceDirectiveRepository extends JpaRepository<AdvanceDirective, UUID> {

    List<AdvanceDirective> findByPatient_Id(UUID patientId);

    /** Acting hospital only — still read by the consent-sharing path until E9 #65. */
    List<AdvanceDirective> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    /** E9 #59 — the readable set from {@code RecordAccessPolicy.readableHospitalIds}. */
    List<AdvanceDirective> findByPatient_IdAndHospital_IdIn(UUID patientId, Collection<UUID> hospitalIds);
}
