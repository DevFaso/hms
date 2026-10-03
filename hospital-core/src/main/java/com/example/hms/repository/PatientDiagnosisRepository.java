package com.example.hms.repository;

import com.example.hms.model.PatientDiagnosis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PatientDiagnosisRepository extends JpaRepository<PatientDiagnosis, UUID> {

    /**
     * A patient's diagnoses in one status recorded at any of {@code hospitalIds}
     * — the scoped chart read. Rows with no hospital (written before V171) are
     * never in this result.
     */
    List<PatientDiagnosis> findByPatient_IdAndStatusAndHospital_IdInOrderByDiagnosedAtDesc(
        UUID patientId, String status, Collection<UUID> hospitalIds);

    /**
     * The pre-V171 rows that carry no hospital. Only for a reader entitled to
     * unscoped rows (a verified super-admin); the patient's own portal read
     * uses {@link #findByPatient_IdOrderByDiagnosedAtDesc}.
     */
    List<PatientDiagnosis> findByPatient_IdAndStatusAndHospitalIsNullOrderByDiagnosedAtDesc(
        UUID patientId, String status);

    List<PatientDiagnosis> findByPatient_IdOrderByDiagnosedAtDesc(UUID patientId);
}
