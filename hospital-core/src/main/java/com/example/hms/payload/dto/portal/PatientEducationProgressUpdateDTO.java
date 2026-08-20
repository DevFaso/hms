package com.example.hms.payload.dto.portal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a patient may change about their own education progress.
 *
 * <p>Deliberately narrower than the staff {@code PatientEducationProgressRequestDTO}:
 * no {@code providerNotes} (clinician commentary) and no {@code comprehensionStatus}
 * — the status is derived server-side from the patient's actions so they cannot
 * stamp themselves CONFIRMED_UNDERSTANDING without confirming.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientEducationProgressUpdateDTO {

    @Min(value = 0, message = "Progress percentage must be between 0 and 100")
    @Max(value = 100, message = "Progress percentage must be between 0 and 100")
    private Integer progressPercentage;

    @Min(value = 1, message = "Rating must be between 1 and 5")
    @Max(value = 5, message = "Rating must be between 1 and 5")
    private Integer rating;

    @Size(max = 2000, message = "Feedback cannot exceed 2000 characters")
    private String feedback;

    /** Patient confirms they understood the material. */
    private Boolean confirmedUnderstanding;

    /** Patient flags that they need this explained. */
    private Boolean needsClarification;

    @Size(max = 1000, message = "Clarification request cannot exceed 1000 characters")
    private String clarificationRequest;
}
