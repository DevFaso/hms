package com.example.hms.payload.dto.superadmin;

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
public class SecurityPolicyBaselineResponseDTO {

    private UUID id;
    private String baselineVersion;
    private String title;
    private String summary;
    private String enforcementLevel;
    private Integer policyCount;
    private String controlObjectivesJson;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
