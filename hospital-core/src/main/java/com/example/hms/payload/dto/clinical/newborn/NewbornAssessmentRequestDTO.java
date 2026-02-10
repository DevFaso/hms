package com.example.hms.payload.dto.clinical.newborn;

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

    private LocalDateTime assessmentTime;
    private Boolean lateEntry;
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
}
