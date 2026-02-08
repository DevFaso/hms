package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request payload for creating or updating patient insurance.
 * At creation, a patient is REQUIRED (matches controller rule).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PatientInsuranceRequestDTO {

    @Schema(description = "Insurance ID (for updates only)")
    private UUID id;

    @NotNull
    @Schema(description = "Patient to attach this insurance to (required at creation)", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID patientId;

    @NotBlank
    @Schema(description = "Insurance provider name", example = "Blue Cross")
    private String providerName;

    @NotBlank
    @Schema(description = "Policy number", example = "POL123456789")
    private String policyNumber;

    @Schema(description = "Group number", example = "GRP98765")
    private String groupNumber;

    @Schema(description = "Subscriber name", example = "John Doe")
    private String subscriberName;

    @Schema(description = "Relationship of subscriber to patient", example = "Self")
    private String subscriberRelationship;

    @Schema(description = "Effective date of the policy")
    private LocalDate effectiveDate;

    @Schema(description = "Expiration date of the policy")
    private LocalDate expirationDate;

    @Builder.Default
    @JsonProperty("primary") // keep JSON as "primary" while field remains isPrimary
    @Schema(description = "Whether this is the primary insurance", defaultValue = "true")
    private boolean primary = true;

    @AssertTrue(message = "expirationDate must be on/after effectiveDate")
    @Schema(hidden = true)
    public boolean isDateRangeValid() {
        if (effectiveDate == null || expirationDate == null) return true;
        return !expirationDate.isBefore(effectiveDate);
    }
}
