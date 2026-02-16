package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterNoteResponseDTO extends EncounterNoteBaseDTO {
    private UUID id;
    private LocalDateTime updatedAt;
    private List<EncounterNoteAddendumResponseDTO> addenda;
    private List<EncounterLinkedArtifactDTO> linkedArtifacts;
}
