package com.example.hms.payload.dto.pharmacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Policy rule 6 (V345): one « à facturer » deferral. {@code canDecide} tells the screen whether the reader may
 * approve or reject it (an approver of its kind who did not ask for it); {@code canReview} whether they may review
 * an urgency override (a reviewer who did not make it); {@code canExecute} whether they may hand a fill over on it
 * (approved, and not approved by them).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispenseBillingDeferralDTO {
    private UUID id;
    private UUID prescriptionId;
    private UUID patientId;
    private String patientName;
    /** The patient's MRN at the deferral's own hospital (never another hospital's). */
    private String patientMrn;
    private String medicationName;
    private UUID dispenseId;
    private UUID admissionId;
    private String kind;
    private String status;
    private String reason;
    private UUID requestedBy;
    private String requestedByName;
    private LocalDateTime requestedAt;
    private UUID decidedBy;
    private String decidedByName;
    private LocalDateTime decidedAt;
    private String decisionNote;
    private BigDecimal creditLimit;
    private String coverReference;
    private UUID executedBy;
    private String executedByName;
    private LocalDateTime executedAt;
    private BigDecimal amountDue;
    /**
     * An executed deferral only: what its fills still owe now (charge less payments and claims a money desk
     * stands behind; a cancelled fill owes nothing). {@code amountDue} is what was owed when they left.
     */
    private BigDecimal stillDue;
    private UUID reviewedBy;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String reviewNote;
    private String cancelledByName;
    private LocalDateTime cancelledAt;
    private String cancelReason;
    /**
     * QA D3406 (r70): an open deferral only: the charge of the prescription prepared fill and what it still owes,
     * so the approver sees the amount before setting a ceiling. Null when no fill is prepared.
     */
    private BigDecimal fillCharge;
    private BigDecimal fillDue;
    /**
     * QA D3408 (r70): a third-party cover only: the insurer share by the patient coverage (recorded at approval,
     * estimated before) and the patient share, which is paid at the checkout before the hand-over.
     */
    private BigDecimal insurerShare;
    private BigDecimal patientShare;
    private boolean canDecide;
    private boolean canReview;
    private boolean canExecute;
}
