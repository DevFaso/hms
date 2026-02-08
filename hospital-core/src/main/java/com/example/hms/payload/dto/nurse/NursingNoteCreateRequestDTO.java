package com.example.hms.payload.dto.nurse;

import com.example.hms.model.NursingNoteTemplate;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class NursingNoteCreateRequestDTO {

    @NotNull
    private UUID patientId;

    private UUID hospitalId;

    @NotNull
    private NursingNoteTemplate template;

    @Size(max = 4000)
    private String dataSubjective;

    @Size(max = 4000)
    private String dataObjective;

    @Size(max = 4000)
    private String dataAssessment;

    @Size(max = 4000)
    private String dataPlan;

    @Size(max = 4000)
    private String dataImplementation;

    @Size(max = 4000)
    private String dataEvaluation;

    @Size(max = 2000)
    private String actionSummary;

    @Size(max = 2000)
    private String responseSummary;

    @Size(max = 2000)
    private String educationSummary;

    @Size(max = 4000)
    private String narrative;

    private boolean lateEntry;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mmXXX")
    private OffsetDateTime eventOccurredAt;

    private Double readabilityScore;

    @NotNull
    private Boolean attestAccuracy;

    @NotNull
    private Boolean attestSpellCheck;

    @NotNull
    private Boolean attestNoAbbreviations;

    private boolean signAndComplete;

    @Size(max = 200)
    private String signedByName;

    @Size(max = 200)
    private String signedByCredentials;

    @Builder.Default
    @Valid
    private List<NursingNoteEducationDTO> educationEntries = new ArrayList<>();

    @Builder.Default
    @Valid
    private List<NursingNoteInterventionDTO> interventionEntries = new ArrayList<>();

    private String shiftLabel;
}
