package com.example.hms.payload.dto;

import com.example.hms.enums.EncounterNoteTemplate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Shared base class for encounter note fields,
 * eliminating duplication between request and response DTOs.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class EncounterNoteBaseDTO {
    private EncounterNoteTemplate template;
    private String chiefComplaint;
    private String historyOfPresentIllness;
    private String reviewOfSystems;
    private String physicalExam;
    private String diagnosticResults;
    private String subjective;
    private String objective;
    private String assessment;
    private String plan;
    private String implementation;
    private String evaluation;
    private String patientInstructions;
    private String summary;
    private Boolean lateEntry;
    private LocalDateTime eventOccurredAt;
    private LocalDateTime documentedAt;
    private Boolean attestAccuracy;
    private Boolean attestNoAbbreviations;
    private Boolean attestSpellCheck;
    private LocalDateTime signedAt;
    private String signedByName;
    private String signedByCredentials;
}
