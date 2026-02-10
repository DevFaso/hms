package com.example.hms.payload.dto.ultrasound;

import com.example.hms.enums.UltrasoundOrderStatus;
import com.example.hms.enums.UltrasoundScanType;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for ultrasound order with embedded report data when available.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UltrasoundOrderResponseDTO {

    private UUID id;

    private UUID patientId;

    private String patientDisplayName;

    private String patientMrn;

    private UUID hospitalId;

    private String hospitalName;

    private UltrasoundScanType scanType;

    private UltrasoundOrderStatus status;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime orderedDate;

    private String orderedBy;

    private Integer gestationalAgeAtOrder;

    private String clinicalIndication;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate scheduledDate;

    private String scheduledTime;

    private String appointmentLocation;

    private String priority;

    private Boolean isHighRiskPregnancy;

    private String highRiskNotes;

    private String specialInstructions;

    private Integer scanCountForPregnancy;

    // Embedded report data when available
    private UltrasoundReportResponseDTO report;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime cancelledAt;

    private String cancelledBy;

    private String cancellationReason;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
