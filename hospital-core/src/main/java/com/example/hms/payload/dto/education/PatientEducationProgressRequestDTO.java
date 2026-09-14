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
    @NotNull(message = "{patientEducationProgress.resourceId.required}")
    private UUID resourceId;
    
    private EducationComprehensionStatus comprehensionStatus;
    
    @Min(value = 0, message = "{patientEducationProgress.progressPercentage.range}")
    @Max(value = 100, message = "{patientEducationProgress.progressPercentage.range}")
    private Integer progressPercentage;
    
    @Min(value = 1, message = "{patientEducationProgress.rating.range}")
    @Max(value = 5, message = "{patientEducationProgress.rating.range}")
    private Integer rating;
    
    private String feedback;
    private Boolean needsClarification;
    private String clarificationRequest;
    private Boolean confirmedUnderstanding;
    private String providerNotes;
}
