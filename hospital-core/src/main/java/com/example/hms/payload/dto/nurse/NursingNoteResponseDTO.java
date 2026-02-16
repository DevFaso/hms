package com.example.hms.payload.dto.nurse;

import com.example.hms.model.NursingNoteTemplate;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NursingNoteResponseDTO {

    private UUID id;

    private UUID patientId;

    private String patientName;

    private String patientMrn;

    private UUID hospitalId;

    private String hospitalName;

    private UUID authorUserId;

    private UUID authorStaffId;

    private String authorName;

    private String authorCredentials;

    private NursingNoteTemplate template;

    private String dataSubjective;

    private String dataObjective;

    private String dataAssessment;

    private String dataPlan;

    private String dataImplementation;

    private String dataEvaluation;

    private String actionSummary;

    private String responseSummary;

    private String educationSummary;

    private String narrative;

    private boolean lateEntry;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime eventOccurredAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime documentedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime signedAt;

    private String signedByName;

    private String signedByCredentials;

    private boolean attestAccuracy;

    private boolean attestSpellCheck;

    private boolean attestNoAbbreviations;

    private Double readabilityScore;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime updatedAt;

    @Builder.Default
    private List<NursingNoteEducationDTO> educationEntries = new ArrayList<>();

    @Builder.Default
    private List<NursingNoteInterventionDTO> interventionEntries = new ArrayList<>();

    @Builder.Default
    private List<NursingNoteAddendumResponseDTO> addenda = new ArrayList<>();
}
