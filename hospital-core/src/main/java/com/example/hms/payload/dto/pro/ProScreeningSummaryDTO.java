package com.example.hms.payload.dto.pro;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Where one instrument stands on a postpartum care plan — the cadence hook
 * (Tier 2 item 47). {@code due} is true while the plan is open and has
 * never been screened; after that the last result is shown and repeating
 * is the clinician's call.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProScreeningSummaryDTO {

    private String instrumentCode;
    /** False when the instrument has not been loaded yet — the UI must not offer it. */
    private boolean instrumentAvailable;
    private boolean due;
    private UUID lastResponseId;
    private LocalDateTime lastAdministeredAt;
    private Integer lastTotalScore;
    private Integer maxScore;
    private Boolean lastScreenPositive;
    private Boolean lastCriticalItemPositive;
    /** True when a self-harm-positive response is still waiting for someone to acknowledge it. */
    private boolean escalationOpen;
}
