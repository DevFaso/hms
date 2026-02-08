package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response payload for patient insurance details.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PatientInsuranceResponseDTO {

    @Schema(description = "Unique ID of the patient insurance")
    private UUID id;

    @Schema(description = "Patient ID linked to this insurance")
    private UUID patientId;

    @Schema(description = "Hospital ID if the insurance is linked within a specific hospital context (staff path)")
    private UUID hospitalId;

    @Schema(description = "Assignment (URHA) used when a staff member linked this insurance")
    private UUID assignmentId;

    @Schema(description = "User ID of the actor who performed the last link/unlink/update")
    private UUID linkedByUserId;

    @Schema(description = "Acting mode used by the actor (PATIENT or STAFF)")
    private String linkedAs;

    @Schema(description = "Insurance provider name", example = "Blue Cross")
    private String providerName;

    @Schema(description = "Policy number", example = "POL123456789")
    private String policyNumber;

    @Schema(description = "Group number", example = "GRP98765")
    private String groupNumber;

    @Schema(description = "Subscriber's full name", example = "John Doe")
    private String subscriberName;

    @Schema(description = "Relationship of subscriber to patient", example = "Self")
    private String subscriberRelationship;

    @Schema(description = "Effective date of coverage")
    private LocalDate effectiveDate;

    @Schema(description = "Expiration date of coverage")
    private LocalDate expirationDate;

    @JsonProperty("primary")
    @Schema(description = "Whether this is the primary insurance", defaultValue = "true")
    private boolean isPrimary;

    @Schema(description = "Date and time this record was created")
    private LocalDateTime createdAt;

    @Schema(description = "Date and time this record was last updated")
    private LocalDateTime updatedAt;
}
