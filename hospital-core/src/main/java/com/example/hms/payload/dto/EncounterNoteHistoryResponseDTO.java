package com.example.hms.payload.dto;

import com.example.hms.enums.EncounterNoteTemplate;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterNoteHistoryResponseDTO {
    private UUID id;
    private UUID noteId;
    private EncounterNoteTemplate template;
    private LocalDateTime changedAt;
    private String changedBy;
    private String changeType;
    private String contentSnapshot;
    private String metadataSnapshot;
}
