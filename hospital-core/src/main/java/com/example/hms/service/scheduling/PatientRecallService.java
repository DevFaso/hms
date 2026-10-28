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

    /**
     * QA D985: booking the visit a recall asks for schedules the recall.
     * With {@code recallId} (the desk booked from the recall list) that recall,
     * when it is open and the appointment's patient's at its hospital. Without
     * it, the open recall that visibly asks for this visit: same patient and
     * hospital, the recall's department or preferred clinician is the
     * appointment's, and the visit is no earlier than 30 days before the recall
     * is due. A recall naming neither is linked only explicitly. Nothing
     * happens when none qualifies.
     */
    void scheduleOpenRecallFor(com.example.hms.model.Appointment appointment, java.util.UUID recallId);

    /**
     * QA D985: the visit a recall was scheduled with took place; the recall is
     * fulfilled (CLOSED).
     */
    void completeRecallsOf(java.util.UUID appointmentId);

    /**
     * QA D985: the appointment a recall was scheduled with was cancelled or
     * missed; the recall goes back to PENDING, unlinked, so the desk and the
     * reminder sweep chase the patient again.
     */
    void reopenRecallsOf(java.util.UUID appointmentId);
}
