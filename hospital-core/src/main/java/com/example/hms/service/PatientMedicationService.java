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
}
