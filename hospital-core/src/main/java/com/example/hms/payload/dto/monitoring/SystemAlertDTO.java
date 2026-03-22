package com.example.hms.payload.dto.monitoring;

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
public class SystemAlertDTO {

    private UUID id;
    private String alertType;
    private String severity;
    private String title;
    private String description;
    private String source;
    private boolean acknowledged;
    private String acknowledgedBy;
    private LocalDateTime acknowledgedAt;
    private boolean resolved;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
