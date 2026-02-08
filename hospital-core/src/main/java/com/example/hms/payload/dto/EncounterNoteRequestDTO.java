package com.example.hms.payload.dto;

import com.example.hms.enums.EncounterNoteTemplate;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterNoteRequestDTO {

    @NotNull
    private EncounterNoteTemplate template;

    private UUID authorStaffId;
    private UUID authorUserId;
    private String authorName;
    private String authorCredentials;

    @Size(max = 4096)
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

    private List<EncounterNoteLinkRequestDTO> customLinks;
    private Set<UUID> linkedLabOrderIds;
    private Set<UUID> linkedPrescriptionIds;
    private Set<UUID> linkedReferralIds;
}
