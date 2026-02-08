package com.example.hms.payload.dto.consultation;

import com.example.hms.enums.ConsultationType;
import com.example.hms.enums.ConsultationUrgency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class ConsultationRequestDTO {

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    private UUID encounterId;

    @NotNull(message = "Consultation type is required")
    private ConsultationType consultationType;

    @NotBlank(message = "Specialty is required")
    @Size(max = 100, message = "Specialty must not exceed 100 characters")
    private String specialtyRequested;

    @NotBlank(message = "Reason for consultation is required")
    private String reasonForConsult;

    private String clinicalQuestion;

    private String relevantHistory;

    private String currentMedications;

    @NotNull(message = "Urgency level is required")
    private ConsultationUrgency urgency;

    private UUID preferredConsultantId;

    private LocalDateTime preferredDateTime;

    private Boolean isCurbside;
}
