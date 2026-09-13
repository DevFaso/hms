package com.example.hms.repository;

import java.util.Collection;
import com.example.hms.model.chart.PatientChartUpdate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientChartUpdateRepository extends JpaRepository<PatientChartUpdate, UUID> {

    Optional<PatientChartUpdate> findTopByPatient_IdAndHospital_IdOrderByVersionNumberDesc(UUID patientId, UUID hospitalId);

    List<PatientChartUpdate> findByPatient_IdAndHospital_IdOrderByVersionNumberDesc(UUID patientId, UUID hospitalId);

    /** E9 #59 — the readable set from {@code RecordAccessPolicy.readableHospitalIds}. */
    Page<PatientChartUpdate> findByPatient_IdAndHospital_IdIn(UUID patientId, Collection<UUID> hospitalIds, Pageable pageable);
}
