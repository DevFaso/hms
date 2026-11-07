package com.example.hms.payload.dto.clinical.newborn;

import com.example.hms.payload.dto.VitalSignLimits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import com.example.hms.enums.NewbornEducationTopic;
import com.example.hms.enums.NewbornFollowUpAction;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewbornAssessmentRequestDTO {

    private UUID hospitalId;
    private UUID registrationId;
    private UUID recordedByStaffId;

    /**
     * The delivery this newborn came from, when the birth was recorded in the
     * Labor &amp; Delivery module.
     *
     * <p>Optional on purpose: a newborn may be transferred in already born, or
     * predate the L&amp;D module. An assessment without it is complete, it simply
     * has no partograph to trace back to.
     */
    private UUID deliveryRecordId;

    @com.example.hms.validation.NotInClinicalFuture

    private LocalDateTime assessmentTime;
    private Boolean lateEntry;
    @com.example.hms.validation.NotInClinicalFuture
    private LocalDateTime originalEntryTime;

    @Min(0)
    @Max(10)
    private Integer apgarOneMinute;

    @Min(0)
    @Max(10)
    private Integer apgarFiveMinute;

    @Min(0)
    @Max(10)
    private Integer apgarTenMinute;

    private String apgarNotes;

    @DecimalMin(value = VitalSignLimits.TEMPERATURE_MIN_C, message = "{vitals.temperature.range}")
    @DecimalMax(value = VitalSignLimits.TEMPERATURE_MAX_C, message = "{vitals.temperature.range}")
    private Double temperatureCelsius;
    // QA D1424: 1000 bpm, 500 /min and SpO2 150 % reached the chart. The same
    // bounds as every other vitals form (VitalSignLimits). There is no separate
    // neonatal range in the repo, and these were set to span neonates.
    @Min(value = VitalSignLimits.HEART_RATE_MIN, message = "{vitals.heartRate.range}")
    @Max(value = VitalSignLimits.HEART_RATE_MAX, message = "{vitals.heartRate.range}")
    private Integer heartRateBpm;
    @Min(value = VitalSignLimits.RESPIRATORY_RATE_MIN, message = "{vitals.respiratoryRate.range}")
    @Max(value = VitalSignLimits.RESPIRATORY_RATE_MAX, message = "{vitals.respiratoryRate.range}")
    private Integer respirationsPerMin;
    @Min(value = VitalSignLimits.SYSTOLIC_MIN, message = "{vitals.systolic.range}")
    @Max(value = VitalSignLimits.SYSTOLIC_MAX, message = "{vitals.systolic.range}")
    private Integer systolicBpMmHg;
    @Min(value = VitalSignLimits.DIASTOLIC_MIN, message = "{vitals.diastolic.range}")
    @Max(value = VitalSignLimits.DIASTOLIC_MAX, message = "{vitals.diastolic.range}")
    private Integer diastolicBpMmHg;
    @Min(value = VitalSignLimits.SPO2_MIN, message = "{vitals.spo2.range}")
    @Max(value = VitalSignLimits.SPO2_MAX, message = "{vitals.spo2.range}")
    private Integer oxygenSaturationPercent;
    @Min(value = VitalSignLimits.GLUCOSE_MIN, message = "{vitals.glucose.range}")
    @Max(value = VitalSignLimits.GLUCOSE_MAX, message = "{vitals.glucose.range}")
    private Integer glucoseMgDl;

    private String examGeneralAppearance;
    private String examHeadNeck;
    private String examChestLungs;
    private String examCardiac;
    private String examAbdomen;
    private String examGenitourinary;
    private String examSkin;
    private String examNeurological;
    private String examMusculoskeletal;
    private String examNotes;

    private String followUpNotes;

    @Builder.Default
    private Set<NewbornFollowUpAction> followUpActions = new HashSet<>();

    @Builder.Default
    private Set<NewbornEducationTopic> parentEducationTopics = new HashSet<>();

    private String parentEducationNotes;
    private Boolean parentEducationCompleted;

    private Boolean escalationRecommended;
    private Boolean respiratorySupportInitiated;
    private Boolean glucoseProtocolInitiated;
    private Boolean thermoregulationSupportInitiated;

    /** QA D1424 (D111): a diastolic at or above the systolic is a swapped entry. */
    @com.example.hms.validation.ReportedOn("diastolicBpMmHg")
    @jakarta.validation.constraints.AssertTrue(message = "{vitals.bloodPressure.invalid}")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBloodPressureOrdered() {
        return VitalSignLimits.bloodPressureOrdered(systolicBpMmHg, diastolicBpMmHg);
    }
}
