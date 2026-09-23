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
    /** LOW / HIGH when the abnormal result's side of the range is known. */
    private com.example.hms.enums.AbnormalDirection abnormalDirection;
    private LocalDateTime resultedAt;
    private String orderingContext;
}
