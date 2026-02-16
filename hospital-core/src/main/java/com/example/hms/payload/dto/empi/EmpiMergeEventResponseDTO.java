package com.example.hms.payload.dto.empi;

import com.example.hms.enums.empi.EmpiMergeType;
import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.UUID;

@Value
@Builder
public class EmpiMergeEventResponseDTO {
    UUID id;
    UUID primaryIdentityId;
    UUID secondaryIdentityId;
    UUID organizationId;
    UUID hospitalId;
    UUID departmentId;
    EmpiMergeType mergeType;
    String resolution;
    String notes;
    String undoToken;
    UUID mergedBy;
    OffsetDateTime mergedAt;
}
