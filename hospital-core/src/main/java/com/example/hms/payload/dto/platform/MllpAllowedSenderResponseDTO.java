package com.example.hms.payload.dto.platform;

import java.time.LocalDateTime;
import java.util.UUID;

public record MllpAllowedSenderResponseDTO(
    UUID id,
    UUID hospitalId,
    String hospitalName,
    String sendingApplication,
    String sendingFacility,
    String description,
    boolean active,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
