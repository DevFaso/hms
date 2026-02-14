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
    private String patientFullName;
    private String patientEmail;
    private String hospitalName;
    private String labTestName;
    private String labTestCode;
    private LocalDateTime orderDatetime;
    private String status;
    private String clinicalIndication;
    private String medicalNecessityNote;
    private String notes;
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

