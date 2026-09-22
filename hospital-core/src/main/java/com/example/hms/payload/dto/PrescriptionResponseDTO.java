package com.example.hms.payload.dto;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PrescriptionResponseDTO {

    private UUID id;

    private UUID patientId;
    private String patientFullName;
    private String patientEmail;

    private UUID staffId;
    private String staffFullName;

    private UUID encounterId;
    private UUID hospitalId;
    /**
     * Display name of the prescription's hospital.
     * Required by the super-admin cross-tenant list view
     * (docs/super-admin-cross-tenant-design.md) so the global "Hospital"
     * column can render without an N+1 lookup.
     */
    private String hospitalName;

    private String medicationName;
    private String medicationDisplayName;

    private String dosage;
    private String frequency;
    private String duration;
    private String route;
    private String instructions;
    private String notes;

    private String status;

    /**
     * Signature evidence (P2 #16). Null on a SIGNED prescription means it was
     * signed before V118 and cannot be verified — deliberately distinguishable
     * from a prescription that carries a real digest.
     */
    private String signatureValue;
    private String signatureAlgorithm;
    private java.time.LocalDateTime signedAt;
    private java.util.UUID signedByStaffId;

    /**
     * Safeguard state (P2 #15), surfaced so a refused sign or dispense is
     * explicable: the response says WHICH safeguard is declared and whether it
     * has been satisfied, instead of leaving the caller to guess.
     */
    private boolean controlledSubstance;
    private boolean requiresCosign;
    private java.time.LocalDateTime twoFactorVerifiedAt;
    private java.time.LocalDateTime cosignedAt;
    private java.util.UUID cosignedByStaffId;

    /* ── Pharmacist verification (Tier 2 item 33) ───────────────────────── */

    /** Whether this prescription is in scope for the verification gate at all. */
    private boolean requiresPharmacistVerification;

    /** Null when unverified — either never verified, or invalidated by an edit. */
    private java.time.LocalDateTime pharmacistVerifiedAt;

    private java.util.UUID pharmacistVerifiedByUserId;
    private String pharmacistVerifiedByName;
    private String pharmacistVerificationNote;

    /* ── Pharmacy and dispatch state (gap G7) ────────────────────────────── */

    /**
     * Where the prescription went: the partner pharmacy it was routed to or
     * the community pharmacy it was dispatched to by SMS. Null for an order
     * still at, or filled by, the hospital's own dispensary. Contact details
     * are the pharmacy's, so they are fine on the patient-facing copy too.
     */
    private UUID pharmacyId;
    private String pharmacyName;
    private String pharmacyContact;

    /** Community-pharmacy dispatch (PrescriptionSmsDispatchService): SMS / SENT / when. */
    private String dispatchChannel;
    private String dispatchStatus;
    private LocalDateTime dispatchedAt;

    /**
     * The latest pharmacy outcome, when the current status is one the
     * pharmacy owns (DISPENSED, PARTIALLY_FILLED, PENDING_STOCK, the partner
     * states, PRINTED_FOR_PATIENT, PENDING_CLARIFICATION); null while the
     * order is still the prescriber's. {@code lastPharmacyEventAt} is the
     * exact instant where one is recorded (clarification requested, dispatch)
     * and otherwise the row's last update, which for a pharmacy status is the
     * transition into it.
     */
    private String lastPharmacyEvent;
    private LocalDateTime lastPharmacyEventAt;

    /* ── Pharmacist clarification (gap G5) — not on the patient-facing copy ── */

    private String clarificationReason;
    private LocalDateTime clarificationRequestedAt;
    private String clarificationResponse;
    private LocalDateTime clarificationResolvedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * The copy a patient may read (gap G7): where to go stays, the
     * pharmacist-to-prescriber clarification exchange — a professional
     * consultation about the order — comes off. Mutates and returns this.
     */
    public PrescriptionResponseDTO withoutClarificationExchange() {
        this.clarificationReason = null;
        this.clarificationRequestedAt = null;
        this.clarificationResponse = null;
        this.clarificationResolvedAt = null;
        // The pharmacy-event pair restates the exchange when the exchange is
        // what the pharmacy last did: lastPharmacyEventAt IS the instant the
        // pharmacist asked. Stripping the narrative while leaving its
        // timestamp behind would be a leak by another name.
        if ("PENDING_CLARIFICATION".equals(this.lastPharmacyEvent)) {
            this.lastPharmacyEvent = null;
            this.lastPharmacyEventAt = null;
        }
        return this;
    }

    /**
     * CDS rule-engine cards produced when the prescription was last
     * created or updated. Empty when the engine had nothing to flag.
     * Null on read-only responses where the engine did not run.
     */
    private List<CdsCard> cdsAdvisories;
}
