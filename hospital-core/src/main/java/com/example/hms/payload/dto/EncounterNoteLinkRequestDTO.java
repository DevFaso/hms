package com.example.hms.payload.dto;

import com.example.hms.enums.EncounterNoteLinkType;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterNoteLinkRequestDTO {
    @NotNull
    private EncounterNoteLinkType artifactType;
    @NotNull
    private UUID artifactId;
    private String artifactCode;
    private String artifactDisplay;
    private String artifactStatus;
}
