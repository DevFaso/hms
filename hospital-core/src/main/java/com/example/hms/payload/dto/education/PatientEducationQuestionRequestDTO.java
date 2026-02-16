package com.example.hms.payload.dto.education;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientEducationQuestionRequestDTO {
    private UUID resourceId;
    
    @NotBlank(message = "Question text is required")
    @Size(min = 5, max = 2000, message = "Question must be between 5 and 2000 characters")
    private String questionText;
    
    private Boolean isUrgent;
    private Boolean requiresInPersonDiscussion;
    
    @Size(max = 3000, message = "Answer text cannot exceed 3000 characters")
    private String answerText;
    
    private Boolean isAnswered;
    private Boolean appointmentScheduled;
}
