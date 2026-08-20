package com.example.hms.payload.dto.portal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A question the authenticated patient asks about their education material.
 *
 * <p>Narrower than the staff {@code PatientEducationQuestionRequestDTO}, which
 * also carries the answer fields — a patient must not be able to submit a
 * question pre-marked as answered.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientEducationQuestionSubmitDTO {

    /** Optional — a question may be about a specific assigned resource. */
    private UUID resourceId;

    @NotBlank(message = "Question text is required")
    @Size(min = 5, max = 2000, message = "Question must be between 5 and 2000 characters")
    private String questionText;

    private Boolean isUrgent;

    private Boolean requiresInPersonDiscussion;
}
