package com.example.hms.service;

import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import java.util.List;
import java.util.UUID;

public interface PatientLabResultService {

    /**
     * Staff view: every row, released or not, with {@code released} set so a
     * UI can label a preliminary value.
     */
    List<PatientLabResultResponseDTO> getLabResultsForPatient(UUID patientId, UUID hospitalId, int limit);

    /**
     * B3 — the patient's own view (portal, proxy, dashboard). Unreleased rows
     * are kept so the patient knows a result is expected, but they carry only
     * the test identity and {@code PENDING}: no value, unit, reference range,
     * notes or performer until the lab releases the result.
     */
    List<PatientLabResultResponseDTO> getLabResultsForPatientPortal(UUID patientId, UUID hospitalId, int limit);
}
