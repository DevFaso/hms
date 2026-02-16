package com.example.hms.payload.dto.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
public class EmpiIdentityAliasDTO {
    UUID id;
    EmpiAliasType aliasType;
    String aliasValue;
    String sourceSystem;
    boolean active;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
