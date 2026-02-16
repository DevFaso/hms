package com.example.hms.payload.dto.education;

import com.example.hms.enums.EducationComprehensionStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientEducationProgressRequestDTO {
    @NotNull(message = "Resource ID is required")
    private UUID resourceId;
    
    private EducationComprehensionStatus comprehensionStatus;
    
    @Min(value = 0, message = "Progress percentage must be between 0 and 100")
    @Max(value = 100, message = "Progress percentage must be between 0 and 100")
    private Integer progressPercentage;
    
    @Min(value = 1, message = "Rating must be between 1 and 5")
    @Max(value = 5, message = "Rating must be between 1 and 5")
    private Integer rating;
    
    private String feedback;
    private Boolean needsClarification;
    private String clarificationRequest;
    private Boolean confirmedUnderstanding;
    private String providerNotes;
}
