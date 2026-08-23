package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.payload.dto.CheckInRequestDTO;
import com.example.hms.payload.dto.CheckInResponseDTO;
import com.example.hms.payload.dto.DuplicateCandidateDTO;
import com.example.hms.payload.dto.EligibilityAttestationRequestDTO;
import com.example.hms.payload.dto.FlowBoardDTO;
import com.example.hms.payload.dto.FrontDeskPatientSnapshotDTO;
import com.example.hms.payload.dto.InsuranceIssueDTO;
import com.example.hms.payload.dto.ReceptionDashboardSummaryDTO;
import com.example.hms.payload.dto.ReceptionQueueItemDTO;
import com.example.hms.payload.dto.WaitlistEntryRequestDTO;
import com.example.hms.payload.dto.WaitlistEntryResponseDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ReceptionService {

    // ── MVP 9 ─────────────────────────────────────────────────────────────────
    ReceptionDashboardSummaryDTO getDashboardSummary(LocalDate date, UUID hospitalId);

    List<ReceptionQueueItemDTO> getQueue(LocalDate date, UUID hospitalId, String status,
                                         UUID departmentId, UUID providerId);

    FrontDeskPatientSnapshotDTO getPatientSnapshot(UUID patientId, UUID hospitalId);

    // ── MVP 10 ────────────────────────────────────────────────────────────────
    List<InsuranceIssueDTO> getInsuranceIssues(LocalDate date, UUID hospitalId);

    List<ReceptionQueueItemDTO> getPaymentsPending(LocalDate date, UUID hospitalId);

    FlowBoardDTO getFlowBoard(LocalDate date, UUID hospitalId, UUID departmentId);

    // ── MVP 11 ────────────────────────────────────────────────────────────────
    List<DuplicateCandidateDTO> getDuplicateCandidates(String name, String dob, String phone,
                                                        UUID hospitalId);

    WaitlistEntryResponseDTO addToWaitlist(WaitlistEntryRequestDTO request, UUID hospitalId,
                                           String actorUsername);

    List<WaitlistEntryResponseDTO> getWaitlist(UUID hospitalId, UUID departmentId, String status);

    /**
     * Offer a concrete slot to a waiting entry (P3 #22): holds the slot for
     * the offer window, stamps the entry OFFERED and notifies the patient.
     */
    WaitlistEntryResponseDTO offerWaitlistSlot(UUID waitlistId, UUID hospitalId, UUID slotId,
                                               Integer expiresInHours);

    /**
     * Front-desk confirmation that the patient wants the offered slot: books
     * it (the appointment owns the time from then on) and closes the entry.
     */
    WaitlistEntryResponseDTO acceptWaitlistOffer(UUID waitlistId, UUID hospitalId);

    /** The patient passed on the offer: free the slot, back to WAITING. */
    WaitlistEntryResponseDTO declineWaitlistOffer(UUID waitlistId, UUID hospitalId);

    /**
     * Return lapsed offers to WAITING and free their slots, so an unanswered
     * offer never strands an entry or a slot. Unscoped — the sweep is a
     * system actor. Returns how many entries were reset.
     */
    int reconcileExpiredWaitlistOffers();

    void closeWaitlistEntry(UUID waitlistId, UUID hospitalId);

    void attestEligibility(UUID insuranceId, UUID hospitalId, String actorUsername,
                           EligibilityAttestationRequestDTO request);

    // ── MVP 11: Flow board ────────────────────────────────────────────────────
    void updateEncounterStatus(UUID encounterId, EncounterStatus status, UUID hospitalId, String callerUsername);

    // ── MVP 1: Patient Check-In ───────────────────────────────────────────────
    CheckInResponseDTO checkInPatient(CheckInRequestDTO request, UUID hospitalId, String actorUsername);
}
