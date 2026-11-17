package com.example.hms.payload.dto.clinical.labor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** QA D1592: a baby the delivery registered as its own patient. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryNewbornDTO {

    private UUID patientId;
    private String displayName;
    private String mrn;
    private int birthOrder;
    /** True when the delivery's live-birth count no longer matches the babies registered. */
    private boolean needsReview;
    /** Why it needs review: LIVE_BIRTH_COUNT_LOWERED or NO_LONGER_LIVE_BIRTH; null otherwise. */
    private String reviewReason;
}
