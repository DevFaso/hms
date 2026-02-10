package com.example.hms.payload.dto.education;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientEducationQuestionResponseDTO {
    private UUID id;
    private UUID patientId;
    private UUID resourceId;
    private UUID hospitalId;
    private String questionText;
    private Boolean isUrgent;
    private Boolean isAnswered;
    private String answerText;
    private UUID answeredByStaffId;
    private LocalDateTime answeredAt;
    private Boolean requiresInPersonDiscussion;
    private Boolean appointmentScheduled;
    private String providerNotes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
