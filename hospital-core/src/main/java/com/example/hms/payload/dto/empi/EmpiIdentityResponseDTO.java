package com.example.hms.payload.dto.empi;

import com.example.hms.enums.empi.EmpiIdentityStatus;
import com.example.hms.enums.empi.EmpiResolutionState;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Value
@Builder
public class EmpiIdentityResponseDTO {
    UUID id;
    String empiNumber;
    UUID patientId;
    UUID organizationId;
    UUID hospitalId;
    UUID departmentId;
    EmpiIdentityStatus status;
    EmpiResolutionState resolutionState;
    boolean active;
    String sourceSystem;
    String mrnSnapshot;
    String metadata;
    List<EmpiIdentityAliasDTO> aliases;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
