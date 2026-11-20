package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultResponseDTO {

    private String id;
    private String labOrderId;
    private String labOrderCode;
    /** QA D963: the order's clinical priority (ROUTINE, URGENT, STAT), so the lab sees a STAT result first. */
    private String orderPriority;
    private String labTestCode;
    private String patientId;
    private String patientFullName;
    private String patientEmail;
    private String hospitalId;
    private String hospitalName;
    /**
     * B1: the laboratory that ran the order, when it was not the ordering
     * hospital's own. Null for an in-house order. Releasing is that
     * laboratory's act, so a reader needs this to know whether the release
     * control belongs to them.
     */
    private String performingHospitalId;
    private String orderedByName;
    private String labTestName;

    /** QA D648: the order's test definition no longer exists; labTestName is the "deleted test" label. */
    private boolean labTestDefinitionMissing;
    private String resultValue;
    private String resultUnit;
    private LocalDateTime resultDate;
    private String notes;
    private List<LabResultReferenceRangeDTO> referenceRanges;

    /**
     * Round 18: the range this result was graded against (its sex and age
     * band, in its unit), or null when none graded it. The screen marks it
     * among {@link #referenceRanges}.
     */
    private LabResultReferenceRangeDTO gradedReferenceRange;
    /** QA D834: the expected answer of a qualitative test ("Négatif"), shown where it has no range. */
    private String expectedResult;
    private List<LabResultTrendPointDTO> trendHistory;
    private String severityFlag;
    /**
     * True when the test has reference ranges but none is in this result's
     * unit: the result is NOT graded (severityFlag UNSPECIFIED), raises no
     * range-derived critical alert and is never auto-released. Staff surfaces
     * show it as "not graded: units differ". An analyser's own abnormal flag
     * (OBX-8) is separate and still honoured.
     */
    private boolean unitMismatch;

    /**
     * QA D1394: a range with limits applies, but the value does not settle a grade
     * against it (a comparator such as "&gt;1" on a range ending at 6.5). severityFlag
     * is UNSPECIFIED and no range alert was raised: a person must review it.
     */
    private boolean gradeUnknown;
    private boolean acknowledged;
    private LocalDateTime acknowledgedAt;
    private String acknowledgedBy;

    // ── Critical-value loop (P0 #5). Previously the escalation stamps existed
    // on the entity but appeared in no DTO, so no surface could show that a
    // critical result had been chased — or how many times. ──
    private LocalDateTime criticalNotifiedAt;
    private LocalDateTime criticalEscalatedAt;
    private short criticalEscalationLevel;
    private LocalDateTime criticalReadBackAt;
    private String criticalReadBackBy;
    /** What was repeated back — persisted even on a mismatch, for the audit. */
    private String criticalReadBackValue;
    private boolean released;
    private LocalDateTime releasedAt;
    private String releasedByFullName;
    /**
     * D58: the user who released this result is the one who entered it (a
     * single-reviewer release). Shown beside the release so no reader takes
     * it for a second person's check.
     */
    private boolean selfReleased;
    /** D81: the released result this one corrects. */
    private String supersedesResultId;
    /** D81: set on a released result once a correction replaced it; the correction is the current value. */
    private String supersededByResultId;
    /** The user who entered the result (its assignment's user), so a client can ask for the self-release confirmation up front. */
    private String enteredByUserId;
    private LocalDateTime signedAt;
    private String signedBy;
    private String signatureValue;
    private String signatureNotes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Provenance. Both were on the entity and in no DTO, so the release
    // worklist could not tell an analyzer's number from a typed one, or a
    // preliminary from a final — and `released` is no substitute there, since
    // every row on that queue is unreleased. ──

    /**
     * MSH-10 of the HL7 v2 message that produced this row; null for a result
     * a person entered. Non-null is what "came from an instrument" means.
     */
    private String sourceMessageControlId;

    /**
     * OBX-11 as the analyzer sent it ({@code F} final, {@code P} preliminary,
     * {@code C} corrected, …, HL7 table 0085); null when the analyzer did not
     * say and always for a hand-entered row.
     */
    private String observationResultStatus;

    // ── Detail context (r44 result drawer). Filled on the single-result read
    // only, from what is recorded; null when nothing is. ──

    /** The collected specimen's type as the laboratory recorded it. */
    private String specimenType;
    /** When that specimen was collected. */
    private LocalDateTime specimenCollectedAt;
    /** The laboratory the order was sent to, when it was not the ordering hospital's own (B1). */
    private String performingLabName;

    /** r63 (NFS panels): true when this result carries one value per panel component. */
    private boolean panel;
    /** r63 (NFS panels): the component values, each graded against its own ranges. */
    @Builder.Default
    private List<LabResultComponentResponseDTO> components = java.util.Collections.emptyList();

    /** r63 D2304: why this result was released while the test's QC was rejected, and when. */
    private String qcOverrideReason;
    private LocalDateTime qcOverrideAt;

    /** r63 D2305: the laboratory's communication of this critical result at release. */
    private String criticalCommunicatedTo;
    private LocalDateTime criticalCommunicatedAt;
    private String criticalCommunicationReadBack;
    private String criticalCommunicatedBy;

    /**
     * r65 D2614: who the patient is, to check during a critical call when two
     * patients have near-identical names: the file number at the ordering
     * hospital, the date of birth and the sex. Filled on the release worklist.
     */
    private String patientMrn;
    private java.time.LocalDate patientDateOfBirth;
    private String patientGender;

    /**
     * r65 D2613: the rejected QC run that holds this result's release (the
     * same run the release gate refuses on), so the worklist says so before
     * the release dialog is filled; null when nothing holds it. Filled on the
     * release worklist.
     */
    private LabQcHoldDTO qcHold;

    /** r65 D2613: the rejected QC run holding a release: which control level, when, by whom. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LabQcHoldDTO {
        private java.util.UUID runId;
        private String qcLevel;
        private LocalDateTime recordedAt;
        private String recordedBy;
        private String sdFlag;
    }
}
