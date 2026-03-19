package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LabReflexRuleResponseDTO {

    private UUID id;
    private UUID triggerTestDefinitionId;
    private String triggerTestDefinitionName;
    private String condition;
    private UUID reflexTestDefinitionId;
    private String reflexTestDefinitionName;
    private boolean active;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
