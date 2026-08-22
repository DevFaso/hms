package com.example.hms.payload.dto.scheduling;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VisitTypeResponseDTO {

    private UUID id;
    private UUID departmentId;
    private String departmentName;
    private String code;
    private String name;
    private String description;
    private int durationMinutes;
    private boolean patientBookable;
    private boolean active;
}
