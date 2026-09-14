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
    
    @NotBlank(message = "{patientEducationQuestion.questionText.required}")
    @Size(min = 5, max = 2000, message = "{patientEducationQuestion.questionText.size}")
    private String questionText;
    
    private Boolean isUrgent;
    private Boolean requiresInPersonDiscussion;
    
    @Size(max = 3000, message = "{patientEducationQuestion.answerText.size}")
    private String answerText;
    
    private Boolean isAnswered;
    private Boolean appointmentScheduled;
}
