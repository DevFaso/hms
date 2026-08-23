package com.example.hms.service.scheduling;

import com.example.hms.enums.RecallStatus;
import com.example.hms.payload.dto.scheduling.RecallRequestDTO;
import com.example.hms.payload.dto.scheduling.RecallResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * Patient recalls (P3 #22) — the return visits the practice owes patients.
 * Two feeds: checkout follow-up requests (created by EncounterService) and
 * manual desk entry (created here). A sweep notifies patients as due dates
 * approach; the desk books and links an appointment to resolve one.
 */
public interface PatientRecallService {

    RecallResponseDTO createRecall(RecallRequestDTO request, UUID hospitalId, String actorUsername);

    /** Ordered soonest-due first. Status and patient filters are optional. */
    List<RecallResponseDTO> getRecalls(UUID hospitalId, RecallStatus status, UUID patientId);

    /** The visit happened or the need lapsed. Never deletes. */
    RecallResponseDTO closeRecall(UUID recallId, UUID hospitalId);

    /** Created in error or no longer wanted. Never deletes. */
    RecallResponseDTO cancelRecall(UUID recallId, UUID hospitalId);

    /** An appointment was booked for this recall: mark SCHEDULED and link it. */
    RecallResponseDTO linkAppointment(UUID recallId, UUID hospitalId, UUID appointmentId);
}
