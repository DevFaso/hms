package com.example.hms.payload.dto.clinical.newborn;

import com.example.hms.payload.dto.VitalSignLimits;
import jakarta.validation.constraints.DecimalMin;
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

    @DecimalMin(value = "0", message = "{newborn.vitals.negative}")
    private Double temperatureCelsius;
    // QA D1424: warnings and escalation, never a limit that prevents saving an observation
    // (NewbornAlertRules holds the sources). Only what cannot exist by definition is refused:
    // a negative number, a saturation above 100 %. A high value saves, flagged to remeasure,
    // and its urgent alert fires.
    @Min(value = 0, message = "{newborn.vitals.negative}")
    private Integer heartRateBpm;
    @Min(value = 0, message = "{newborn.vitals.negative}")
    private Integer respirationsPerMin;
    @Min(value = 0, message = "{newborn.vitals.negative}")
    private Integer systolicBpMmHg;
    @Min(value = 0, message = "{newborn.vitals.negative}")
    private Integer diastolicBpMmHg;
    @Min(value = VitalSignLimits.SPO2_MIN, message = "{vitals.spo2.range}")
    @Max(value = VitalSignLimits.SPO2_MAX, message = "{vitals.spo2.range}")
    private Integer oxygenSaturationPercent;
    /** The unit is in the field's name. A value in another unit comes as {@link #glucoseValue}. */
    @Min(value = 0, message = "{newborn.vitals.negative}")
    private Integer glucoseMgDl;
    /** QA D1424: a glucose as entered; refused on {@link #glucoseUnit} without its unit. */
    @DecimalMin(value = "0", message = "{newborn.vitals.negative}")
    private Double glucoseValue;
    private com.example.hms.enums.GlucoseUnit glucoseUnit;

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

    /** QA D1424: 5.6 means nothing without its unit, and the alerts read the number. */
    @com.example.hms.validation.ReportedOn("glucoseUnit")
    @jakarta.validation.constraints.AssertTrue(message = "{maternity.newborn.glucoseUnitRequired}")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isGlucoseUnitGiven() {
        return glucoseValue == null || glucoseUnit != null;
    }

    /** QA D1424 (D111): a diastolic at or above the systolic is a swapped entry. */
    @com.example.hms.validation.ReportedOn("diastolicBpMmHg")
    @jakarta.validation.constraints.AssertTrue(message = "{vitals.bloodPressure.invalid}")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBloodPressureOrdered() {
        return VitalSignLimits.bloodPressureOrdered(systolicBpMmHg, diastolicBpMmHg);
    }
}
