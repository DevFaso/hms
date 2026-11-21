package com.example.hms.payload.dto;

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
public class PatientVitalSignResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID registrationId;
    private UUID hospitalId;
    private String hospitalName;
    private UUID recordedByStaffId;
    private UUID recordedByAssignmentId;
    private String recordedByName;
    private String source;
    private Double temperatureCelsius;
    private Integer heartRateBpm;
    private Integer respiratoryRateBpm;
    private Integer systolicBpMmHg;
    private Integer diastolicBpMmHg;
    private Integer spo2Percent;
    private Integer bloodGlucoseMgDl;
    private Double weightKg;
    private Double heightCm;
    private Double headCircumferenceCm;
    private String bodyPosition;
    /**
     * QA D1169: the nurse's note with the pain score and the fundal height
     * in front of it, in the reader's language (VitalSignNotes).
     */
    private String notes;
    /** QA D1169: the 0-10 pain score as a number. */
    private Integer painScore;
    /** QA D1169: the fundal height in cm as a number. */
    private Integer fundalHeightCm;
    private boolean clinicallySignificant;

    /* ── NEWS2 (P3 #25b) ─────────────────────────────────────────────
       Computed on read from this bundle's parameters. When incomplete,
       the score covers only what was recorded and newsMissing names the
       gaps — consumers must render the incompleteness, never hide it. */
    private Boolean onOxygen;
    private com.example.hms.enums.ConsciousnessLevel consciousnessLevel;
    private Integer newsScore;
    /** LOW | LOW_MEDIUM | MEDIUM | HIGH */
    private String newsRiskBand;
    private Boolean newsComplete;
    private java.util.List<String> newsMissing;
    /**
     * Review of #1237: set (OBSTETRIC or NEWBORN) when NEWS2 is not validated for this patient at this
     * reading (News2Applicability); the score fields are then empty and the chart says why.
     */
    private String newsNotValidated;

    /* ── Maternal early warning (policy 2026-11-20, PROVISIONAL) ───────
       For a pregnant or postpartum woman (newsNotValidated = OBSTETRIC) the
       chart shows this in place of « NEWS2 non validé ». Computed on read
       from this set, never stored; pending a named obstetric clinician's
       sign-off, and every consumer must say « provisoire ». */
    /** The additional concerns the recorder ticked on this set (MaternalConcern names). */
    private java.util.List<String> maternalConcerns;
    /** The points of the vital signs that were recorded (0-12); null when the score does not apply. */
    private Integer mewsScore;
    /** LOW | LOW_MEDIUM | MEDIUM | HIGH: the level the set is answered at (a concern or a RED alert raises it). */
    private String mewsTier;
    private Boolean mewsComplete;
    /** The scored vital signs this set lacks (RESPIRATORY_RATE, SPO2, TEMPERATURE, PULSE, SYSTOLIC, DIASTOLIC). */
    private java.util.List<String> mewsMissing;
    /** The additional concerns read on this set: the ticked ones, oxygen and a lowered consciousness. */
    private java.util.List<String> mewsConcerns;
    /** The CRADLE add-on alerts: SEVERE_HYPERTENSION (red), SHOCK (yellow), SEVERE_SHOCK (red). */
    private java.util.List<String> mewsAlerts;
    /** Medical review within this many minutes (0 = immediate); null at the low level. */
    private Integer mewsReviewWithinMinutes;
    /** The pulse was scored with the bands that apply from 48 h after the birth. */
    private Boolean mewsPostBirthPulse;

    private LocalDateTime recordedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** QA D1564: on a write only, the newborn warnings on this set; null on a read. */
    private java.util.List<NewbornVitalAlertDTO> newbornAlerts;

    /** QA D1890, r54 (provisional): the obstetric severe-blood-pressure warning (pregnancy, or 42 days after a birth); empty when none. */
    private java.util.List<NewbornVitalAlertDTO> postpartumAlerts;

    /** r54 (provisional): the adult severe-blood-pressure warning (WARNING, or URGENT with a sign of organ damage); empty when none. */
    private java.util.List<NewbornVitalAlertDTO> hypertensionAlerts;

    /**
     * Policy 2026-11-20 (provisional): on a write only, the maternal early warning of a pregnant or
     * postpartum woman's set (the level of concern when a review is due, and each shock-index alert);
     * empty when none.
     */
    private java.util.List<NewbornVitalAlertDTO> maternalAlerts;
}
