package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for a lab/imaging result awaiting physician review.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DoctorResultQueueItemDTO {

    private UUID id;
    private String patientName;
    private UUID patientId;
    private String testName;
    private String resultValue;
    private String abnormalFlag;  // NORMAL, ABNORMAL, CRITICAL
    private LocalDateTime resultedAt;
    private String orderingContext;
}
