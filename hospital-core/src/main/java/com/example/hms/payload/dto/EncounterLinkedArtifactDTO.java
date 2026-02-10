package com.example.hms.payload.dto;

import com.example.hms.enums.EncounterNoteLinkType;
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
public class EncounterLinkedArtifactDTO {
    private UUID artifactId;
    private EncounterNoteLinkType artifactType;
    private String artifactCode;
    private String artifactDisplay;
    private String artifactStatus;
    private LocalDateTime linkedAt;
}
