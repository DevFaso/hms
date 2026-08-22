package com.example.hms.payload.dto;

import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.enums.MicroGrowthResult;
import com.example.hms.enums.MicroSusceptibilityInterpretation;
import com.example.hms.enums.MicroSusceptibilityMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full culture report: culture -> isolates -> susceptibilities (P3 #19).
 * Field names are the wire contract the portal mirrors field-for-field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MicroCultureResponseDTO {

    private UUID id;
    private UUID labOrderId;
    private String labOrderCode;
    private String labTestName;
    private UUID patientId;
    private String patientName;
    private UUID hospitalId;
    private String hospitalName;
    private UUID specimenId;
    private String specimenAccessionNumber;
    private String specimenSource;
    private LocalDateTime collectedAt;
    private MicroCultureStatus status;
    private MicroGrowthResult growthResult;
    private String gramStain;
    private LocalDateTime finalizedAt;
    private String finalizedByName;
    private LocalDateTime correctedAt;
    private String correctionReason;
    private String reportedByName;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Isolate> isolates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Isolate {
        private UUID id;
        private Integer isolateNumber;
        private String organismName;
        private String organismCode;
        private String growthQuantity;
        private String notes;
        private List<Susceptibility> susceptibilities;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Susceptibility {
        private UUID id;
        private String antibioticName;
        private String antibioticCode;
        private MicroSusceptibilityMethod method;
        private String micValue;
        private MicroSusceptibilityInterpretation interpretation;
        private String notes;
    }
}
