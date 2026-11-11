package com.example.hms.payload.dto.ultrasound;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for ultrasound report data.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UltrasoundReportResponseDTO extends UltrasoundReportBaseDTO {

    private UUID id;

    private UUID ultrasoundOrderId;

    // Report finalization
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime reportFinalizedAt;

    private String reportFinalizedBy;

    private Boolean reportReviewedByProvider;

    private String providerReviewNotes;

    private Boolean patientNotified;

    /**
     * QA D1525: false when the report predates D1525 and its observations,
     * anomalies answer and fetus count may be server defaults nobody stated.
     */
    private Boolean observationsStated;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime patientNotifiedAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
