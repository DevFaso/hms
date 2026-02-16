package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultResponseDTO {

    private String id;
    private String labOrderCode;
    private String patientFullName;
    private String patientEmail;
    private String hospitalName;
    private String labTestName;
    private String resultValue;
    private String resultUnit;
    private LocalDateTime resultDate;
    private String notes;
    private List<LabResultReferenceRangeDTO> referenceRanges;
    private List<LabResultTrendPointDTO> trendHistory;
    private String severityFlag;
    private boolean acknowledged;
    private LocalDateTime acknowledgedAt;
    private String acknowledgedBy;
    private boolean released;
    private LocalDateTime releasedAt;
    private String releasedByFullName;
    private LocalDateTime signedAt;
    private String signedBy;
    private String signatureValue;
    private String signatureNotes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
