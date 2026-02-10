package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.SecurityPolicyApprovalStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SecurityPolicyApprovalSummaryDTO {

    UUID id;
    String policyName;
    String changeType;
    String requestedBy;
    SecurityPolicyApprovalStatus status;
    LocalDateTime submittedAt;
    LocalDateTime requiredBy;
    String summary;
    String baselineVersion;
    String severity;
}
