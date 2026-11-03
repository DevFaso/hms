package com.example.hms.payload.dto.ultrasound;

import com.example.hms.enums.UltrasoundScanType;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request DTO for creating a new ultrasound order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UltrasoundOrderRequestDTO {

    @NotNull(message = "{ultrasoundOrder.patientId.required}")
    private UUID patientId;

    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;

    @NotNull(message = "{ultrasoundOrder.scanType.required}")
    private UltrasoundScanType scanType;

    private Integer gestationalAgeAtOrder; // weeks

    private String clinicalIndication;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate scheduledDate;

    private String scheduledTime;

    private String appointmentLocation;

    @Builder.Default
    // QA D1373 class (round 31): one of ClinicalPriority, refused by name otherwise. Upper
    // case only: the mapper stores the value as sent, and the worklists compare codes.
    @jakarta.validation.constraints.Pattern(regexp = com.example.hms.enums.ClinicalPriority.CODES,
        message = "{validation.priority.invalid}")
    private String priority = "ROUTINE"; // ROUTINE, URGENT, STAT

    @Builder.Default
    private Boolean isHighRiskPregnancy = false;

    private String highRiskNotes;

    private String specialInstructions;

    private Integer scanCountForPregnancy;
}
