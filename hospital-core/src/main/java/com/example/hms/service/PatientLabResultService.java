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

    /**
     * QA D1362 (round 31): how many current results the patient has - the
     * rows {@link #getLabResultsForPatientPortal(UUID, int, int)} pages
     * through, counted after the same superseded / corrected-away filter.
     */
    long countLabResultsForPatientPortal(UUID patientId);

    /**
     * QA D2730 (r65): the grantor's results as a proxy reads them - the
     * patient's own view less every row a proxy may not see
     * ({@code ProxyRecordFilter}: a sensitive visit or test). The caller has
     * already verified the grant.
     */
    List<PatientLabResultResponseDTO> getLabResultsForProxy(UUID patientId, int limit);

    /** QA D2730: the paged proxy view, filtered before the offset is skipped. */
    List<PatientLabResultResponseDTO> getLabResultsForProxy(UUID patientId, int offset, int limit);

    /** QA D2730: the proxy's total, counted after the same filter. */
    long countLabResultsForProxy(UUID patientId);
}
