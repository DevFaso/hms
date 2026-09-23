package com.example.hms.payload.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /prescriptions/{id}/resolve-clarification} (gap G5):
 * the prescriber's answer. Optional — the doctor may have edited the order
 * instead of, or as well as, replying.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrescriptionClarificationResolutionDTO {

    @Size(max = 1000)
    private String response;
}
