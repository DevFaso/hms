package com.example.hms.payload.dto.isolation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Stop a precaution.
 *
 * <p>A reason is required. Lifting isolation is a clinical decision — usually
 * a negative result or a completed treatment course — and the chart should
 * say which, because the next person to ask "why is this patient off
 * precautions" is asking a safety question.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscontinuePrecautionRequestDTO {

    @NotNull
    @Size(max = 500)
    private String discontinuationReason;

    private UUID discontinuedByStaffId;
}
