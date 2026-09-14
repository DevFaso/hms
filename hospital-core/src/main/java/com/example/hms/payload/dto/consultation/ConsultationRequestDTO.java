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

    @NotNull(message = "{consultation.patientId.required}")
    private UUID patientId;

    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;

    private UUID encounterId;

    @NotNull(message = "{consultation.consultationType.required}")
    private ConsultationType consultationType;

    @NotBlank(message = "{consultation.specialty.required}")
    @Size(max = 100, message = "{consultation.specialty.size}")
    private String specialtyRequested;

    @NotBlank(message = "{consultation.reasonForConsult.required}")
    private String reasonForConsult;

    private String clinicalQuestion;

    private String relevantHistory;

    private String currentMedications;

    @NotNull(message = "{consultation.urgency.required}")
    private ConsultationUrgency urgency;

    private UUID preferredConsultantId;

    private LocalDateTime preferredDateTime;

    private Boolean isCurbside;
}
