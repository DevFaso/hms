package com.example.hms.repository;

import com.example.hms.model.PatientAllergy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientAllergyRepository extends JpaRepository<PatientAllergy, UUID> {

    List<PatientAllergy> findByPatient_Id(UUID patientId);

    List<PatientAllergy> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    /**
     * QA D110 — the allergy rows a reader acting at {@code hospitalId} may see. E9 #56 lets
     * allergies follow the patient across hospitals, but a patient who has opted out of
     * record sharing (an active, unrevoked opt-out) keeps every other hospital's rows to
     * that hospital: only the acting hospital's own rows are returned then.
     */
    @Query("""
        SELECT a FROM PatientAllergy a
        WHERE a.patient.id = :patientId
          AND (a.hospital.id = :hospitalId
               OR NOT EXISTS (
                   SELECT 1 FROM PatientRecordSharingOptOut o
                   WHERE o.patient.id = :patientId AND o.revokedAt IS NULL))
        """)
    List<PatientAllergy> findVisibleAt(@Param("patientId") UUID patientId, @Param("hospitalId") UUID hospitalId);

    /** QA D110 — whether the patient holds an active (unrevoked) record-sharing opt-out. */
    @Query("SELECT COUNT(o) > 0 FROM PatientRecordSharingOptOut o WHERE o.patient.id = :patientId AND o.revokedAt IS NULL")
    boolean hasActiveSharingOptOut(@Param("patientId") UUID patientId);

    Optional<PatientAllergy> findByIdAndPatient_IdAndHospital_Id(UUID id, UUID patientId, UUID hospitalId);

    /** E9 #56 — idempotency guard for the free-text import: has this patient any row from that source? */
    boolean existsByPatient_IdAndSourceSystem(UUID patientId, String sourceSystem);
}
