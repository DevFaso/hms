package com.example.hms.payload.dto.clinical;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for provider review and co-signature of birth plan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BirthPlanProviderReviewRequestDTO {

    @NotNull(message = "Review status is required")
    private Boolean reviewed;

    @NotBlank(message = "Provider signature is required")
    @Size(max = 255)
    private String signature;

    private String comments;
}
