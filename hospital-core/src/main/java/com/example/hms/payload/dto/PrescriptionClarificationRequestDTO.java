package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /prescriptions/{id}/request-clarification} (gap G5):
 * the pharmacist's question to the prescriber.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrescriptionClarificationRequestDTO {

    /** Why the order cannot be filled as written. Required; stored encrypted. */
    @NotBlank(message = "{prescription.clarification.reason.required}")
    @Size(max = 1000)
    private String reason;
}
