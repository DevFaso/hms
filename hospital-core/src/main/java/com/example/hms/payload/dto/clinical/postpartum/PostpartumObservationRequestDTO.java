package com.example.hms.payload.dto.clinical.postpartum;

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
    private Double temperatureCelsius;
    private Integer systolicBpMmHg;
    private Integer diastolicBpMmHg;
    private Integer pulseBpm;
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
