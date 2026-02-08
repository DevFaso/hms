package com.example.hms.repository;

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

    Page<PatientChartUpdate> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId, Pageable pageable);
}
