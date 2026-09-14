package com.example.hms.payload.dto.consultation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeclineConsultationRequestDTO {

    @NotBlank(message = "{declineConsultation.declineReason.required}")
    @Size(max = 500, message = "{declineConsultation.declineReason.size}")
    private String declineReason;
}
