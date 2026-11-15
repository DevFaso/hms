package com.example.hms.repository;

import com.example.hms.enums.TreatmentConsentStatus;
import com.example.hms.model.PatientTreatmentConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PatientTreatmentConsentRepository extends JpaRepository<PatientTreatmentConsent, UUID> {

    @Query("""
            SELECT c FROM PatientTreatmentConsent c
            WHERE c.patient.id = :patientId
                AND (:hospitalId IS NULL OR c.hospital.id = :hospitalId)
            ORDER BY c.consentedAt DESC
    """)
    List<PatientTreatmentConsent> findForPatient(@Param("patientId") UUID patientId,
                                                 @Param("hospitalId") UUID hospitalId);

    boolean existsByPatient_IdAndHospital_IdAndStatus(UUID patientId, UUID hospitalId,
                                                      TreatmentConsentStatus status);

    boolean existsByAppointment_IdAndStatus(UUID appointmentId, TreatmentConsentStatus status);

    /** QA D1591: the consent the patient gave at pre-check-in, for the desk's queue. */
    boolean existsByAppointment_IdAndSourceAndStatus(UUID appointmentId,
                                                     com.example.hms.enums.TreatmentConsentSource source,
                                                     TreatmentConsentStatus status);

    /** QA D1591: the visit's consents already given, which its check-in carries onto the encounter. */
    List<PatientTreatmentConsent> findByAppointment_IdAndStatus(UUID appointmentId, TreatmentConsentStatus status);
}
