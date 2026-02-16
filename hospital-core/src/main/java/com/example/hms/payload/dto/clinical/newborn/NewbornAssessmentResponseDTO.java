package com.example.hms.payload.dto.clinical.newborn;

import com.example.hms.enums.NewbornEducationTopic;
import com.example.hms.enums.NewbornFollowUpAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewbornAssessmentResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private UUID registrationId;

    private LocalDateTime assessmentTime;
    private LocalDateTime documentedAt;
    private boolean lateEntry;
    private LocalDateTime originalEntryTime;

    private Integer apgarOneMinute;
    private Integer apgarFiveMinute;
    private Integer apgarTenMinute;
    private String apgarNotes;

    private Double temperatureCelsius;
    private Integer heartRateBpm;
    private Integer respirationsPerMin;
    private Integer systolicBpMmHg;
    private Integer diastolicBpMmHg;
    private Integer oxygenSaturationPercent;
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

    private boolean escalationRecommended;
    private boolean respiratorySupportInitiated;
    private boolean glucoseProtocolInitiated;
    private boolean thermoregulationSupportInitiated;

    private String followUpNotes;
    private Set<NewbornFollowUpAction> followUpActions;

    private Set<NewbornEducationTopic> parentEducationTopics;
    private String parentEducationNotes;
    private boolean parentEducationCompleted;

    @Builder.Default
    private List<NewbornAssessmentAlertDTO> alerts = Collections.emptyList();
}
