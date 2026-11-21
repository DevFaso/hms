package com.example.hms.service;

import com.example.hms.enums.RefillStatus;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.RefillDecisionRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * Doctor-facing service for triaging patient-initiated medication refill requests.
 * Pairs with the patient-side flow in {@link PatientPortalService}.
 */
public interface RefillApprovalService {

    Page<MedicationRefillResponseDTO> listForProvider(Authentication auth,
                                                      RefillStatus status,
                                                      Pageable pageable);

    long countPendingForProvider(Authentication auth);

    MedicationRefillResponseDTO approve(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision);

    MedicationRefillResponseDTO reject(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision);

    /**
     * Defers the decision on a pending request without approving or denying it —
     * used when the prescriber needs a visit, a lab result or a medication review
     * first. The request stays in the queue and can still be approved or denied.
     */
    MedicationRefillResponseDTO pause(Authentication auth, UUID refillId, RefillDecisionRequestDTO decision);

    /**
     * Policy rule 1 (provisional, QA D2308): the pharmacist reviews a request and forwards it to the prescriber
     * with flags. Decides nothing.
     */
    MedicationRefillResponseDTO pharmacistReview(Authentication auth, UUID refillId, RefillDecisionRequestDTO review);

    /** Policy rule 1: the pharmacist refuses to dispense, with a reason; the patient and the prescriber are told. */
    MedicationRefillResponseDTO pharmacistRefuse(Authentication auth, UUID refillId, RefillDecisionRequestDTO refusal);

    /**
     * Policy rule 1: a refill the prescriber already wrote on the prescription is released by the pharmacy as
     * ordinary dispensing. Refused once the written refills are used: that is a renewal, a prescriber's decision.
     */
    MedicationRefillResponseDTO releaseAsPrescribed(Authentication auth, UUID refillId);
}
