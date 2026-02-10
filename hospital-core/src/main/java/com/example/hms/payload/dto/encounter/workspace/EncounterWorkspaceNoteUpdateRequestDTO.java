package com.example.hms.payload.dto.encounter.workspace;

import com.example.hms.enums.EncounterNoteTemplate;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterWorkspaceNoteUpdateRequestDTO {

    @NotNull
    private EncounterNoteTemplate noteTemplate;

    @NotEmpty
    @Size(min = 1, max = 10)
    private List<@Valid EncounterWorkspaceSectionDTO> sections;

    private List<String> linkedOrderIds;
    private List<String> linkedPrescriptionIds;
    private List<String> linkedReferralIds;

    private LocalDateTime eventDateTime;

    @NotNull
    private LocalDateTime documentationDateTime;

    @NotBlank
    @Size(min = 6, max = 64)
    private String authorizingPin;

    private Boolean lateEntry;
}
