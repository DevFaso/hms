package com.example.hms.payload.dto.superadmin;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SecurityRuleSetResponseDTO {
    String id;
    String code;
    String name;
    String description;
    String enforcementScope;
    Integer ruleCount;
    String metadataJson;
    String publishedBy;
    OffsetDateTime publishedAt;
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;
    List<SecurityRuleDefinitionDTO> rules;
}
