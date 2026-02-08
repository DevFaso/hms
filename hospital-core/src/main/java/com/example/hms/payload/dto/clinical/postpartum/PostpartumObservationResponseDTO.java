package com.example.hms.payload.dto.clinical.postpartum;

import com.example.hms.enums.PostpartumBladderStatus;
import com.example.hms.enums.PostpartumEducationTopic;
import com.example.hms.enums.PostpartumFundusTone;
import com.example.hms.enums.PostpartumLochiaAmount;
import com.example.hms.enums.PostpartumLochiaCharacter;
import com.example.hms.enums.PostpartumMoodStatus;
import com.example.hms.enums.PostpartumSchedulePhase;
import com.example.hms.enums.PostpartumSleepQuality;
import com.example.hms.enums.PostpartumSupportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostpartumObservationResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private UUID carePlanId;

    private LocalDateTime observationTime;
    private LocalDateTime documentedAt;
    private boolean lateEntry;
    private LocalDateTime originalEntryTime;

    private Double temperatureCelsius;
    private Integer systolicBpMmHg;
    private Integer diastolicBpMmHg;
    private Integer pulseBpm;
    private Integer respirationsPerMin;
    private Integer painScore;

    private Integer fundusHeightCm;
    private PostpartumFundusTone fundusTone;
    private PostpartumBladderStatus bladderStatus;
    private PostpartumLochiaAmount lochiaAmount;
    private PostpartumLochiaCharacter lochiaCharacter;
    private String lochiaNotes;
    private String perineumFindings;
    private boolean uterineAtonySuspected;
    private boolean excessiveBleeding;
    private Integer estimatedBloodLossMl;
    private boolean uterotonicGiven;
    private boolean hemorrhageProtocolActivated;

    private boolean foulLochiaOdor;
    private boolean uterineTenderness;
    private boolean chillsOrRigors;

    private PostpartumMoodStatus moodStatus;
    private PostpartumSupportStatus supportStatus;
    private PostpartumSleepQuality sleepStatus;
    private String psychosocialNotes;
    private boolean mentalHealthReferralSuggested;
    private boolean socialSupportReferralSuggested;
    private boolean painManagementReferralSuggested;

    private Set<PostpartumEducationTopic> educationTopics;
    private String educationNotes;
    private boolean educationCompleted;

    private LocalDate postpartumVisitDate;
    private boolean dischargeChecklistComplete;
    private boolean rhImmunoglobulinCompleted;
    private boolean immunizationsUpdated;
    private boolean hemorrhageProtocolConfirmed;
    private boolean uterotonicAvailabilityConfirmed;
    private boolean contactInfoVerified;
    private String followUpContactMethod;
    private String dischargeSafetyNotes;

    private PostpartumSchedulePhase schedulePhaseAtEntry;
    private LocalDateTime nextDueAtSnapshot;
    private LocalDateTime overdueSinceSnapshot;

    private UUID supersedesObservationId;

    private String signoffName;
    private String signoffCredentials;
    private LocalDateTime signedAt;

    private PostpartumScheduleDTO schedule;

    @Builder.Default
    private List<PostpartumAlertDTO> alerts = Collections.emptyList();
}
