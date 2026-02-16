package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuperAdminLabOrderCreateRequestDTO {

    @NotBlank(message = "Organization identifier is required")
    private String organizationIdentifier;

    @NotBlank(message = "Hospital identifier is required")
    private String hospitalIdentifier;

    @NotBlank(message = "Patient identifier is required")
    private String patientIdentifier;

    @NotBlank(message = "Ordering staff identifier is required")
    private String orderingStaffIdentifier;

    private String orderingStaffRole;

    @NotBlank(message = "Lab test identifier is required")
    private String labTestIdentifier;

    @NotBlank(message = "Status is required")
    private String status;

    private String priority;

    private String notes;

    @NotNull(message = "Order date and time is required")
    private LocalDateTime orderDatetime;

    private List<String> testResults;

    @NotBlank(message = "Clinical indication is required")
    @Size(max = 2048)
    private String clinicalIndication;

    @NotBlank(message = "Medical necessity rationale is required")
    @Size(max = 2048)
    private String medicalNecessityNote;

    @NotBlank(message = "Primary ICD-10 diagnosis code is required")
    private String primaryDiagnosisCode;

    @Builder.Default
    private List<String> additionalDiagnosisCodes = new ArrayList<>();

    @NotBlank(message = "Order channel is required")
    private String orderChannel;

    private String orderChannelOther;

    @NotNull(message = "Documentation sharing flag is required")
    private Boolean documentationSharedWithLab;

    private String documentationReference;

    @Pattern(regexp = "\\d{10}", message = "NPI must be a 10-digit numeric identifier")
    private String orderingProviderNpi;

    @NotBlank(message = "Provider signature is required")
    private String providerSignature;

    private LocalDateTime signedAt;

    private Boolean standingOrder;

    private LocalDateTime standingOrderExpiresAt;

    private LocalDateTime standingOrderLastReviewedAt;

    private Integer standingOrderReviewIntervalDays;

    private String standingOrderReviewNotes;
}
