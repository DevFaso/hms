package com.example.hms.payload.dto.encounter.workspace;

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
public class EncounterWorkspaceAddendumDTO {
    private UUID id;
    private String content;
    private String authorName;
    private String authorId;
    private String authorCredentials;
    private LocalDateTime eventDateTime;
    private LocalDateTime documentationDateTime;
    private LocalDateTime createdAt;
    private boolean lateEntry;
}
