package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO;
import com.example.hms.payload.dto.EncounterNoteAddendumResponseDTO;
import com.example.hms.payload.dto.EncounterNoteHistoryResponseDTO;
import com.example.hms.payload.dto.EncounterNoteRequestDTO;
import com.example.hms.payload.dto.EncounterNoteResponseDTO;
import com.example.hms.payload.dto.EncounterRequestDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.NursingIntakeRequestDTO;
import com.example.hms.payload.dto.NursingIntakeResponseDTO;
import com.example.hms.payload.dto.TriageSubmissionRequestDTO;
import com.example.hms.payload.dto.TriageSubmissionResponseDTO;
import com.example.hms.payload.dto.clinical.AfterVisitSummaryDTO;
import com.example.hms.payload.dto.clinical.CheckOutRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@SuppressWarnings("java:S107") // list() method uses individual filter parameters for controller binding
public interface EncounterService {

    EncounterResponseDTO createEncounter(EncounterRequestDTO request, Locale locale);

    EncounterResponseDTO getEncounterById(UUID id, Locale locale);
    List<EncounterResponseDTO> getEncountersByDoctorIdentifier(String identifier, Locale locale);

    Page<EncounterResponseDTO> list(UUID patientId,
                                    UUID staffId,
                                    UUID hospitalId,
                                    LocalDateTime from,
                                    LocalDateTime to,
                                    EncounterStatus status,
                                    Pageable pageable,
                                    Locale locale);

    EncounterResponseDTO updateEncounter(UUID id, EncounterRequestDTO request, Locale locale);

    void deleteEncounter(UUID id, Locale locale);

    // Legacy list endpoints (kept for compatibility)
    List<EncounterResponseDTO> getEncountersByPatientId(UUID patientId, Locale locale);
    List<EncounterResponseDTO> getEncountersByPatientIdentifier(String identifier, Locale locale);
    List<EncounterResponseDTO> getEncountersByDoctorId(UUID staffId, Locale locale);

    EncounterNoteResponseDTO upsertEncounterNote(UUID encounterId, EncounterNoteRequestDTO request, Locale locale);

    EncounterNoteAddendumResponseDTO addEncounterNoteAddendum(UUID encounterId,
                                                              EncounterNoteAddendumRequestDTO request,
                                                              Locale locale);

    java.util.List<EncounterNoteHistoryResponseDTO> getEncounterNoteHistory(UUID encounterId, Locale locale);

    /**
     * MVP 2 — Atomic triage submission: records vitals, chief complaint, acuity,
     * room assignment, and transitions encounter ARRIVED → WAITING_FOR_PHYSICIAN.
     */
    TriageSubmissionResponseDTO submitTriage(UUID encounterId,
                                             TriageSubmissionRequestDTO request,
                                             String actorUsername);

    /**
     * MVP 3 — Nursing intake flowsheet: bulk-updates allergies, records medication
     * reconciliation, stores nursing assessment note, and timestamps intake completion.
     */
    NursingIntakeResponseDTO submitNursingIntake(UUID encounterId,
                                                  NursingIntakeRequestDTO request,
                                                  String actorUsername);

    /**
     * MVP 6 — Check-Out & After-Visit Summary: atomically transitions encounter →
     * COMPLETED, appointment → COMPLETED, records checkout timestamp, stores discharge
     * diagnoses and follow-up instructions, and returns a comprehensive AVS.
     */
    AfterVisitSummaryDTO checkOut(UUID encounterId,
                                  CheckOutRequestDTO request,
                                  String actorUsername);

    /**
     * MVP 6 — Retrieve the After-Visit Summary for a completed (checked-out) encounter.
     * Loads the encounter entity and delegates to CheckOutMapper for full AVS construction.
     */
    AfterVisitSummaryDTO getAfterVisitSummary(UUID encounterId);

    /**
     * Doctor picks up a WAITING_FOR_PHYSICIAN encounter and transitions it to IN_PROGRESS.
     */
    EncounterResponseDTO startEncounter(UUID encounterId, String actorUsername,
                                         boolean isSuperAdmin, UUID callerHospitalId);

    /**
     * Advance a TRIAGE encounter to WAITING_FOR_PHYSICIAN (nurse completes triage from tracker).
     */
    EncounterResponseDTO completeTriage(UUID encounterId);

    /**
     * Doctor finishes examining a patient. Transitions IN_PROGRESS →
     * AWAITING_RESULTS (if pending lab/procedure orders exist) or
     * READY_FOR_DISCHARGE (if no pending orders).
     */
    EncounterResponseDTO completeExamination(UUID encounterId,
                                              boolean isSuperAdmin, UUID callerHospitalId);

    /**
     * Advance AWAITING_RESULTS → READY_FOR_DISCHARGE when all results have been reviewed.
     */
    EncounterResponseDTO markReadyForDischarge(UUID encounterId,
                                                boolean isSuperAdmin, UUID callerHospitalId);
}
