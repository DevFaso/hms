package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * After-Visit Summary (AVS) returned after a successful check-out (MVP 6).
 * Contains a comprehensive summary of the visit for patient review.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterVisitSummaryDTO {

    private UUID encounterId;
    private UUID appointmentId;

    /* --- Visit info --- */
    private LocalDateTime visitDate;
    private String providerName;
    private String departmentName;
    private String hospitalName;

    /* --- Patient --- */
    private UUID patientId;
    private String patientName;

    /* --- Clinical summary --- */
    private String chiefComplaint;
    private List<String> dischargeDiagnoses;
    private String prescriptionSummary;
    private String referralSummary;
    private String followUpInstructions;
    private String patientEducationMaterials;

    /* --- Status --- */
    private String encounterStatus;
    private String appointmentStatus;
    private LocalDateTime checkoutTimestamp;
    /**
     * Provisional platform rule: a note of the visit still waits for its author's signature, so the summary is
     * shown "not finalised". It returns to normal once every note is signed.
     */
    private boolean pendingSignature;

    /* --- Follow-up --- */
    private UUID followUpAppointmentId;
    private String followUpAppointmentDate;

    /*
     * --- Visit summary content (user decision 2026-11-12) ---
     * Each section is empty (null or an empty list) when there is nothing to
     * show, and the portal hides it. Every clinical field here is listed in
     * ReceptionistResponseShaping.REMOVED: the desk reads none of it.
     */

    /** The hospital's own phone and the service's (department's), so the patient knows whom to call. */
    private String hospitalPhone;
    private String departmentPhone;

    /** The last vital-sign set recorded during the visit, at the visit's hospital. */
    private VitalSignsResponse vitalSigns;

    /** The labs and imaging ordered during the visit, each with its status; a result only once released. */
    private List<OrderedTest> orderedTests;

    /** The patient's active allergies, with their severity. */
    private List<Allergy> allergies;

    /** The patient's medicines in force at the visit's hospital: never a draft, a cancelled or a finished one. */
    private List<CurrentMedication> currentMedications;

    /** The signed note's assessment ("A") and plan ("P"), as the clinician entered them. */
    private String clinicalAssessment;
    private String clinicalPlan;

    /** This patient's own recorded history (never a relative's chart). */
    private List<MedicalHistoryEntry> medicalHistory;
    private List<SurgicalHistoryEntry> surgicalHistory;
    private List<FamilyHistoryEntry> familyHistory;
    private SocialHistory socialHistory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    /**
     * A reading carried out to the reader only, never bound from a request: named a Response so
     * BloodPressurePairGuardTest (QA D1424) treats it as read-only, as it does every response DTO.
     */
    public static class VitalSignsResponse {
        private LocalDateTime recordedAt;
        private Double temperatureCelsius;
        private Integer heartRateBpm;
        private Integer respiratoryRateBpm;
        private Integer systolicBpMmHg;
        private Integer diastolicBpMmHg;
        private Integer spo2Percent;
        private Integer bloodGlucoseMgDl;
        private Double weightKg;
        private Double heightCm;
        private Integer painScore;
        /** The set was judged clinically significant when it was recorded (VitalSignJudgement). */
        private boolean flagged;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderedTest {
        /** LAB or IMAGING. */
        private String kind;
        private String name;
        /** The order's status code (LabOrderStatus or ImagingOrderStatus). */
        private String status;
        /** Released results only; empty while none is released. */
        private List<TestResult> results;
        /** A signed imaging report's impression; null until then. */
        private String impression;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestResult {
        private String value;
        private String unit;
        private String referenceRange;
        /** NORMAL, ABNORMAL, ABNORMAL_LOW, ABNORMAL_HIGH or CRITICAL; null when not graded. */
        private String abnormalFlag;
        /** A released correction of an earlier result (QA D1016). */
        private boolean corrected;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Allergy {
        private String allergen;
        /** MILD, MODERATE, SEVERE or LIFE_THREATENING; null when not recorded. */
        private String severity;
        private String reaction;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentMedication {
        private String name;
        private String dosage;
        private String frequency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicalHistoryEntry {
        private String condition;
        /** ProblemStatus code. */
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SurgicalHistoryEntry {
        private String procedure;
        private java.time.LocalDate date;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FamilyHistoryEntry {
        /** As recorded in this patient's chart (free text: "Mère", "Father"...). */
        private String relationship;
        private String condition;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SocialHistory {
        private Boolean tobaccoUse;
        private Boolean alcoholUse;
        private String occupation;
    }
}
