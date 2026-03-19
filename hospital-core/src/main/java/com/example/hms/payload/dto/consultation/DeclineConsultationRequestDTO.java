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

    @NotBlank(message = "Decline reason is required")
    @Size(max = 500, message = "Decline reason must not exceed 500 characters")
    private String declineReason;
}
