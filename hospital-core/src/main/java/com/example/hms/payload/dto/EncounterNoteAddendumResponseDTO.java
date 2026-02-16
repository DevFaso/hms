package com.example.hms.payload.dto;

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
public class EncounterNoteAddendumResponseDTO {
    private UUID id;
    private String content;
    private LocalDateTime eventOccurredAt;
    private LocalDateTime documentedAt;
    private LocalDateTime signedAt;
    private LocalDateTime createdAt;
    private String authorName;
    private String authorCredentials;
    private Boolean attestAccuracy;
    private Boolean attestNoAbbreviations;
    private Boolean lateEntry;
}
