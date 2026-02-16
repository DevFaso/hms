package com.example.hms.payload.dto.encounter.workspace;

import com.example.hms.enums.EncounterNoteTemplate;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterWorkspaceNoteDTO {
    private UUID encounterId;
    private EncounterNoteTemplate template;
    private List<EncounterWorkspaceSectionDTO> sections;
    private List<UUID> linkedOrderIds;
    private List<UUID> linkedPrescriptionIds;
    private List<UUID> linkedReferralIds;
    private LocalDateTime documentationDateTime;
    private LocalDateTime eventDateTime;
    private Boolean lateEntry;
    private LocalDateTime lastUpdatedAt;
    private String lastUpdatedBy;
    private List<EncounterWorkspaceAddendumDTO> addendums;
}
