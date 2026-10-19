package com.example.hms.payload.dto.clinical.postpartum;

import com.example.hms.payload.dto.VitalSignLimits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import com.example.hms.enums.PostpartumBladderStatus;
import com.example.hms.enums.PostpartumEducationTopic;
import com.example.hms.enums.PostpartumFundusTone;
import com.example.hms.enums.PostpartumLochiaAmount;
import com.example.hms.enums.PostpartumLochiaCharacter;
import com.example.hms.enums.PostpartumMoodStatus;
import com.example.hms.enums.PostpartumSleepQuality;
import com.example.hms.enums.PostpartumSupportStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostpartumObservationRequestDTO {

    private UUID carePlanId;
    private UUID hospitalId;
    private UUID registrationId;
    private UUID recordedByStaffId;
    private UUID supersedesObservationId;

    private LocalDateTime observationTime;
    private LocalDateTime deliveryOccurredAt;

    private Boolean stabilizationConfirmed;

    private Integer shiftFrequencyMinutes;
    private Boolean enhancedMonitoring;
    private Integer enhancedMonitoringFrequencyMinutes;
    private Boolean enhancedMonitoringResolved;

    private Boolean dischargeChecklistComplete;

    private LocalDate postpartumVisitDate;
    private Boolean hemorrhageProtocolConfirmed;
    private Boolean uterotonicAvailabilityConfirmed;
    private Boolean rhImmunoglobulinCompleted;
    private Boolean immunizationsUpdated;
    private Boolean contactInfoVerified;
    private String followUpContactMethod;
    private String dischargeSafetyNotes;

    @Builder.Default
    private Set<PostpartumEducationTopic> educationTopics = new HashSet<>();
    private String educationNotes;
    private Boolean educationCompleted;

    private Boolean lateEntry;
    private LocalDateTime originalEntryTime;

    // --- Vital signs ---
    @DecimalMin(value = VitalSignLimits.TEMPERATURE_MIN_C, message = "{vitals.temperature.range}")
    @DecimalMax(value = VitalSignLimits.TEMPERATURE_MAX_C, message = "{vitals.temperature.range}")
    private Double temperatureCelsius;
    // QA D277: a systolic of 900 was stored with no alert. Same physiological
    // bounds as every other vitals form (VitalSignLimits).
    @Min(value = VitalSignLimits.SYSTOLIC_MIN, message = "{vitals.systolic.range}")
    @Max(value = VitalSignLimits.SYSTOLIC_MAX, message = "{vitals.systolic.range}")
    private Integer systolicBpMmHg;
    @Min(value = VitalSignLimits.DIASTOLIC_MIN, message = "{vitals.diastolic.range}")
    @Max(value = VitalSignLimits.DIASTOLIC_MAX, message = "{vitals.diastolic.range}")
    private Integer diastolicBpMmHg;
    @Min(value = VitalSignLimits.HEART_RATE_MIN, message = "{vitals.heartRate.range}")
    @Max(value = VitalSignLimits.HEART_RATE_MAX, message = "{vitals.heartRate.range}")
    private Integer pulseBpm;
    @Min(value = VitalSignLimits.RESPIRATORY_RATE_MIN, message = "{vitals.respiratoryRate.range}")
    @Max(value = VitalSignLimits.RESPIRATORY_RATE_MAX, message = "{vitals.respiratoryRate.range}")
    private Integer respirationsPerMin;

    @Min(0)
    @Max(10)
    private Integer painScore;

    private Integer fundusHeightCm;
    private PostpartumFundusTone fundusTone;
    private PostpartumBladderStatus bladderStatus;
    private PostpartumLochiaAmount lochiaAmount;
    private PostpartumLochiaCharacter lochiaCharacter;
    private String lochiaNotes;
    private String perineumFindings;
    private Boolean uterineAtonySuspected;
    private Boolean excessiveBleeding;
    private Integer estimatedBloodLossMl;
    private Boolean uterotonicGiven;
    private Boolean hemorrhageProtocolActivated;

    private Boolean foulLochiaOdor;
    private Boolean uterineTenderness;
    private Boolean chillsOrRigors;

    private PostpartumMoodStatus moodStatus;
    private PostpartumSupportStatus supportStatus;
    private PostpartumSleepQuality sleepStatus;
    private String psychosocialNotes;
    private Boolean mentalHealthReferralSuggested;
    private Boolean socialSupportReferralSuggested;
    private Boolean painManagementReferralSuggested;

    private String signoffName;
    private String signoffCredentials;
    private LocalDateTime signedAt;
}
