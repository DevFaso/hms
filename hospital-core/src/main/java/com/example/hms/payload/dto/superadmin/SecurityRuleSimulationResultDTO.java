package com.example.hms.payload.dto.superadmin;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SecurityRuleSimulationResultDTO {
    String scenario;
    Integer evaluatedRuleCount;
    Double impactScore;
    List<String> impactedControllers;
    List<String> recommendedActions;
    OffsetDateTime evaluatedAt;
}
