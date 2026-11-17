package com.example.hms.service;

import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;

import java.util.List;
import java.util.UUID;

public interface PatientMedicationService {

    /**
     * STAFF read, from {@code GET /patients/{patientId}/medications}. Goes
     * through the chart gate, and a {@code null} {@code hospitalId} is refused
     * (404, as "no such patient") rather than widened to every hospital.
     */
    List<PatientMedicationResponseDTO> getMedicationsForPatient(UUID patientId, UUID hospitalId, int limit);

    /**
     * The patient (or a proxy the portal has already verified) reading their
     * OWN medications. The caller has established whose record it is; a
     * {@code null} {@code hospitalId} is legitimate here and reads every row,
     * because every row belongs to the patient.
     */
    List<PatientMedicationResponseDTO> getMedicationsForPatientPortal(UUID patientId, UUID hospitalId, int limit);

    /**
     * QA D1399 (D1347 class): one page of the same list as the portal read, with
     * the server's total. {@link #getMedicationsForPatientPortal} bounds the
     * history at its limit and says nothing of the rest; this pages all of it.
     */
    org.springframework.data.domain.Page<PatientMedicationResponseDTO> getMedicationsPageForPatientPortal(
        UUID patientId, org.springframework.data.domain.Pageable pageable);

    /**
     * QA D1362 (round 31): how many medications the patient has in all - every
     * issued prescription, at every hospital, whatever its course status (the
     * rows the portal list draws from; drafts are not medications).
     */
    long countMedicationsForPatientPortal(UUID patientId);
}
