package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SecurityPolicyBaselineRequestDTO {

    @NotBlank
    @Size(max = 160)
    private String title;

    @Size(max = 1000)
    private String summary;

    @NotBlank
    @Size(max = 60)
    private String enforcementLevel;

    @Min(0)
    private Integer policyCount;

    private String controlObjectivesJson;

    @Size(max = 120)
    private String createdBy;
}
