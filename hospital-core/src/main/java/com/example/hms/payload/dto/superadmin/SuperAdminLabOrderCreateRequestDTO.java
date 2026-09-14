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

    @NotBlank(message = "{superAdmin.labOrder.organizationIdentifier.required}")
    private String organizationIdentifier;

    @NotBlank(message = "{superAdmin.labOrder.hospitalIdentifier.required}")
    private String hospitalIdentifier;

    @NotBlank(message = "{superAdmin.labOrder.patientIdentifier.required}")
    private String patientIdentifier;

    @NotBlank(message = "{superAdmin.labOrder.orderingStaffIdentifier.required}")
    private String orderingStaffIdentifier;

    private String orderingStaffRole;

    @NotBlank(message = "{superAdmin.labOrder.labTestIdentifier.required}")
    private String labTestIdentifier;

    @NotBlank(message = "{superAdmin.labOrder.status.required}")
    private String status;

    private String priority;

    private String notes;

    @NotNull(message = "{superAdmin.labOrder.orderDatetime.required}")
    private LocalDateTime orderDatetime;

    private List<String> testResults;

    @NotBlank(message = "{superAdmin.labOrder.clinicalIndication.required}")
    @Size(max = 2048)
    private String clinicalIndication;

    @NotBlank(message = "{superAdmin.labOrder.medicalNecessityNote.required}")
    @Size(max = 2048)
    private String medicalNecessityNote;

    @NotBlank(message = "{superAdmin.labOrder.primaryDiagnosisCode.required}")
    private String primaryDiagnosisCode;

    @Builder.Default
    private List<String> additionalDiagnosisCodes = new ArrayList<>();

    @NotBlank(message = "{superAdmin.labOrder.orderChannel.required}")
    private String orderChannel;

    private String orderChannelOther;

    @NotNull(message = "{superAdmin.labOrder.documentationSharedWithLab.required}")
    private Boolean documentationSharedWithLab;

    private String documentationReference;

    @Pattern(regexp = "\\d{10}", message = "{superAdmin.labOrder.orderingProviderNpi.pattern}")
    private String orderingProviderNpi;

    @NotBlank(message = "{superAdmin.labOrder.providerSignature.required}")
    private String providerSignature;

    private LocalDateTime signedAt;

    private Boolean standingOrder;

    private LocalDateTime standingOrderExpiresAt;

    private LocalDateTime standingOrderLastReviewedAt;

    private Integer standingOrderReviewIntervalDays;

    private String standingOrderReviewNotes;
}
