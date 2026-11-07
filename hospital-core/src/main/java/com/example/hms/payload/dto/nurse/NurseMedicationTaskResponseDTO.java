package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NurseMedicationTaskResponseDTO {

    /** Existing when a recorded death was disputed: a prescriber's review comes first. */
    private boolean heldForReview;

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String medication;
    private String dose;
    private String route;
    /**
     * QA D950: the route as one of the prescribing form's codes (PO, IV, IM,
     * SC, SL, PR, TOP, INH, NEB) whatever way it was stored ("ORAL",
     * "INTRAVENOUS"), so the screen shows it in the reader's language; null
     * when the stored text is not a known route.
     */
    private String routeCode;
    private LocalDateTime dueTime;
    /** DUE, OVERDUE, UPCOMING (next slot not yet open, QA D949) or COMPLETED (no dose left). */
    private String status;
    /**
     * QA D1237: the order's frequency is written but cannot be read as a
     * schedule. The course stays open (status DUE, due now) instead of ending
     * after its first dose; each later dose asks for a reason, and the
     * prescriber should restate the frequency.
     */
    private boolean frequencyUnread;
    /**
     * QA D1419: a continuous infusion ("perfusion continue", "IVSE", "125 ml/h").
     * Once started it has no next slot: the row stays open (status DUE, never
     * OVERDUE, not counted as a dose waiting) so a new bag can be recorded with no
     * reason, and {@link #prnLabel} says so.
     */
    private boolean continuousInfusion;
    /**
     * QA round 30: an as-needed (PRN, "si besoin") order. It is always offered
     * (status DUE, never OVERDUE, not counted as a dose waiting), in a slot of
     * now; a dose before the prescriber's minimum interval has passed since
     * the last dose given is flagged {@link #earlyDose} (Give asks why), and
     * {@link #prnLabel} says from when the next one needs no reason.
     */
    private boolean asNeeded;
    /** QA round 30: the prescriber's minimum hours between two doses of an as-needed order, or null. */
    private Integer prnMinIntervalHours;
    /**
     * QA round 30: the as-needed rule in the reader's language ("Si besoin :
     * au moins 4 h entre deux doses"), or null on a scheduled order.
     */
    private String prnLabel;
    /**
     * QA D1254: the dose is offered now (status DUE) but falls inside the
     * minimum interval since the last dose really given, so giving it asks
     * for a reason (QA D1210). False on every other row.
     */
    private boolean earlyDose;
    /** QA D929: GIVEN, HELD, REFUSED or MISSED for the last closed dose; null when none. */
    private String lastOutcome;
    /** QA D1225: when the last closed dose was recorded. */
    private LocalDateTime lastOutcomeAt;
    /**
     * QA D1225: the reason recorded on the last closed dose (held, refused,
     * or given early); null when it carries none.
     */
    private String lastReason;
    /** QA D1225: the last dose was given before the minimum interval, with a reason. */
    private boolean lastGivenEarly;
    /** QA D1225: the most recent closed doses of this row, newest first, with their reasons. */
    private java.util.List<MarDoseHistoryDTO> doseHistory;

    /* ── Pharmacist verification (Tier 2 item 33) ───────────────────────── */
    //
    // Both flags are on the task, not derived in the UI, so the nurse sees
    // WHY a dose is blocked before pressing the button rather than after.

    /**
     * QA D922: a controlled substance (the prescriber's tick or the hospital
     * catalogue's flag), shown on the MAR row so it is never given as an
     * ordinary drug.
     */
    private boolean controlledSubstance;

    /** True when this medication cannot be given until a pharmacist verifies it. */
    private boolean requiresPharmacistVerification;

    /** True once verified. False plus the flag above means administration is refused. */
    private boolean pharmacistVerified;

    /** QA D922: the clinician who witnessed this controlled dose, once it is recorded. */
    private String witnessName;

    /**
     * QA D799: non-blocking clinical advisories for this dose, today the
     * beta-lactam cross-reactivity warning the prescriber saw. Empty when
     * there is nothing to say; never a block.
     */
    private java.util.List<com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard> cdsAdvisories;
}
