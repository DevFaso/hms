package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabOrderRequestDTO {

    private UUID id;

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    private UUID encounterId;

    @NotBlank(message = "Test name is required")
    private String testName;

    private String testCode;

    @NotBlank(message = "Status is required")
    private String status;

    private String priority;

    @NotBlank(message = "Clinical indication is required")
    @Size(max = 2048, message = "Clinical indication must be at most 2048 characters")
    private String clinicalIndication;

    @NotBlank(message = "Medical necessity narrative is required")
    @Size(max = 2048, message = "Medical necessity note must be at most 2048 characters")
    private String medicalNecessityNote;

    private String notes;

    @NotBlank(message = "A primary ICD-10 diagnosis code is required")
    @Schema(description = "Primary ICD-10 diagnosis code supporting medical necessity")
    private String primaryDiagnosisCode;

    @Builder.Default
    @Schema(description = "Additional ICD-10 diagnosis codes reinforcing medical necessity")
    private List<String> additionalDiagnosisCodes = new ArrayList<>();

    @Schema(description = "Channel used to document or transmit the lab order (ELECTRONIC, PORTAL, PHONE, FAX, EMAIL, WRITTEN, WALK_IN, OTHER)")
    @NotBlank(message = "Order channel is required")
    private String orderChannel;

    @Schema(description = "Free-text descriptor when orderChannel is OTHER")
    private String orderChannelOther;

    @Schema(description = "Whether identical documentation was shared with the destination laboratory")
    @Builder.Default
    @NotNull(message = "Please indicate whether documentation was shared with the lab")
    private Boolean documentationSharedWithLab = Boolean.FALSE;

    @Schema(description = "Reference or tracking identifier for the documentation shared with the lab")
    private String documentationReference;

    @Schema(description = "Ordering provider NPI override if it differs from the staff profile")
    @Pattern(regexp = "\\d{10}", message = "NPI must be a 10-digit numeric identifier")
    private String orderingProviderNpi;

    @Schema(description = "Electronic signature attestation payload from the ordering provider")
    @NotBlank(message = "An electronic signature attestation is required")
    private String providerSignature;

    @Schema(description = "Timestamp of the provider's electronic signature. Defaults to now if absent.")
    private LocalDateTime signedAt;

    @Schema(description = "Indicates if the order is governed by a standing order protocol")
    @Builder.Default
    private Boolean standingOrder = Boolean.FALSE;

    private LocalDateTime standingOrderExpiresAt;

    private LocalDateTime standingOrderLastReviewedAt;

    private Integer standingOrderReviewIntervalDays;

    private String standingOrderReviewNotes;

    private LocalDateTime orderDatetime;

    private LocalDateTime completedAt;

    private List<String> testResults;

    @NotNull(message = "Ordering staff ID is required")
    private UUID orderingStaffId;

    @NotNull(message = "Lab test definition ID is required")
    private UUID labTestDefinitionId;

    @NotNull(message = "Assignment ID is required")
    private UUID assignmentId;
}
