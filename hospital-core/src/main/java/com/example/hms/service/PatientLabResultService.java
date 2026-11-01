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
     * QA D160: the staff view for a caller outside the laboratory. A value
     * the lab has not released is the lab's: the care team sees that a
     * result is pending, not the preliminary number, exactly as the patient
     * does. {@code includeUnreleasedValues} is true only for laboratory staff.
     */
    List<PatientLabResultResponseDTO> getLabResultsForPatient(UUID patientId, UUID hospitalId, int limit,
                                                              boolean includeUnreleasedValues);

    /**
     * B3 — the patient's own view (portal, proxy, dashboard). Unreleased rows
     * are kept so the patient knows a result is expected, but they carry only
     * the test identity and {@code PENDING}: no value, unit, reference range,
     * notes or performer until the lab releases the result.
     */
    List<PatientLabResultResponseDTO> getLabResultsForPatientPortal(UUID patientId, UUID hospitalId, int limit);

    /**
     * QA D1213 (round 23): the patient's own current results from
     * {@code offset}, at most {@code limit}: superseded and corrected-away
     * rows are removed BEFORE the offset is skipped, newest first with a
     * stable tie-break. Same redaction as the method above. No total: a
     * caller asks for limit + 1 to know whether there is more.
     */
    List<PatientLabResultResponseDTO> getLabResultsForPatientPortal(UUID patientId, int offset, int limit);
}
