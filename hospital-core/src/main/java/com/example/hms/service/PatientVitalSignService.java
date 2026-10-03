package com.example.hms.service;

import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientVitalSignService {

    PatientVitalSignResponseDTO recordVital(UUID patientId,
                                            PatientVitalSignRequestDTO request,
                                            UUID recorderUserId);

    /**
     * STAFF read, from {@code GET /patients/{patientId}/vitals/recent}: the
     * chart gate, the readable hospitals (E9 #60), and the cross-hospital
     * reach recorded. A {@code null} {@code hospitalId} is refused (404, as
     * "no such patient") rather than widened to every hospital.
     */
    List<PatientVitalSignResponseDTO> getRecentVitals(UUID patientId,
                                                      UUID hospitalId,
                                                      int limit);

    /**
     * The patient (or a proxy the portal has already verified) reading their
     * OWN vitals: every row, whichever hospital recorded it.
     */
    List<PatientVitalSignResponseDTO> getRecentVitalsForPatientPortal(UUID patientId, int limit);

    /**
     * STAFF read, from {@code GET /patients/{patientId}/vitals}: the chart
     * gate, the caller's hospital only, and a {@code null} {@code hospitalId}
     * refused like {@link #getRecentVitals}.
     */
    List<PatientVitalSignResponseDTO> getVitals(UUID patientId,
                                                UUID hospitalId,
                                                LocalDateTime from,
                                                LocalDateTime to,
                                                int page,
                                                int size);

    /**
     * The newest vitals bundle, embedded in a patient DTO the CALLER has
     * already authorized ({@code PatientServiceImpl.buildPatientDto}, the
     * nurse dashboard). Not a read door of its own: no controller reaches it,
     * and it applies no chart gate, because its callers build lists and a
     * per-row gate would both multiply queries and refuse inside a listing the
     * caller was entitled to. The scope is the caller's: its hospital, or
     * {@code null} only where the caller's own listing is unscoped.
     */
    Optional<PatientResponseDTO.VitalSnapshot> getLatestSnapshot(UUID patientId,
                                                                 UUID hospitalId);
}
