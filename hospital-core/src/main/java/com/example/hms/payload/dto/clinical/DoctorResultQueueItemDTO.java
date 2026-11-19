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
    /** QA D1095: the unit the value is in (the test definition's when none was typed). */
    private String resultUnit;
    private String abnormalFlag;  // NORMAL, ABNORMAL, CRITICAL
    /** LOW / HIGH when the abnormal result's side of the range is known. */
    private com.example.hms.enums.AbnormalDirection abnormalDirection;
    private LocalDateTime resultedAt;
    private String orderingContext;
    /** QA D1016: the result corrects a released value the clinician may already have acted on. */
    private boolean corrected;
    /**
     * QA D2263 (r63): the row's state and links, so the in-basket can act on it: the order it answers
     * (to order a follow-up from), whether it was acknowledged, and whether a critical value still
     * waits for its read-back (the acknowledge is refused until then) or when it was read back.
     */
    private UUID labOrderId;
    private boolean acknowledged;
    private boolean readBackRequired;
    private LocalDateTime readBackAt;
}
