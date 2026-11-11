package com.example.hms.payload.dto;

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
public class LabOrderResponseDTO {

    private String id;
    private String labOrderCode;
    private String patientId;
    private String patientFullName;
    private String patientEmail;
    private String hospitalId;
    private String hospitalName;
    /** Null when the ordering hospital performs the test itself. */
    private String performingHospitalId;
    private String performingHospitalName;
    private String labTestName;

    /** QA D648: the order's test definition no longer exists; labTestName is the "deleted test" label. */
    private boolean labTestDefinitionMissing;
    private String labTestCode;
    /** QA D1095: the test definition's unit, which a result is entered in. */
    private String labTestUnit;
    private LocalDateTime orderDatetime;
    private String status;
    private String priority;
    private String clinicalIndication;
    private String medicalNecessityNote;
    private String notes;

    /** QA D673: why the order was cancelled (null unless CANCELLED). */
    private String cancellationReason;
    private String primaryDiagnosisCode;
    @Builder.Default
    private List<String> additionalDiagnosisCodes = new ArrayList<>();
    private String orderChannel;
    private String orderChannelOther;
    private boolean documentationSharedWithLab;
    private String documentationReference;
    private String orderingProviderNpi;
    private String providerSignatureDigest;
    private LocalDateTime signedAt;
    private String signedByUserId;
    private boolean standingOrder;
    private LocalDateTime standingOrderExpiresAt;
    private LocalDateTime standingOrderLastReviewedAt;
    private LocalDateTime standingOrderReviewDueAt;
    private Integer standingOrderReviewIntervalDays;
    private String standingOrderReviewNotes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

