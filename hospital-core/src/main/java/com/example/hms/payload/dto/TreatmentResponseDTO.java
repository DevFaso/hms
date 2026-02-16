package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentResponseDTO {
    private UUID id;
    private String name;
    private String description;
    private UUID departmentId;
    private UUID hospitalId;
    private String departmentName;
    private String hospitalName;
    private UUID creatorId;
    private String creatorName;
    private BigDecimal price;
    private Integer durationMinutes;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Map<String, ServiceTranslationResponseDTO> translations;

}

