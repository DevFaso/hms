package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseMedicationTaskResponseDTO {

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
    /** QA D929: GIVEN, HELD, REFUSED or MISSED for the last closed dose; null when none. */
    private String lastOutcome;

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
