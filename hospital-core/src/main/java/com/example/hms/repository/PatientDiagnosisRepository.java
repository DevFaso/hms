package com.example.hms.repository;

import com.example.hms.model.PatientDiagnosis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PatientDiagnosisRepository extends JpaRepository<PatientDiagnosis, UUID> {

    List<PatientDiagnosis> findByPatient_IdAndStatusOrderByDiagnosedAtDesc(UUID patientId, String status);

    List<PatientDiagnosis> findByPatient_IdOrderByDiagnosedAtDesc(UUID patientId);
}
