package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for Clinical Alert in Doctor Dashboard
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalAlertDTO {

    private UUID id;

    private String severity; // CRITICAL, URGENT, WARNING, INFO

    private String type; // LAB_CRITICAL, VITAL_ABNORMAL, SEPSIS_RISK, STAT_CONSULT, DRUG_INTERACTION,
                         // OTHER

    private String title;

    private String message;

    private UUID patientId;

    private String patientName;

    private LocalDateTime timestamp;

    private Boolean actionRequired;

    private Boolean acknowledged;

    private String icon;
}
