package com.example.hms.payload.dto.clinical.postpartum;

import com.example.hms.enums.PostpartumSchedulePhase;
import com.example.hms.payload.dto.pro.ProScreeningSummaryDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostpartumScheduleDTO {

    private UUID carePlanId;
    private PostpartumSchedulePhase phase;
    private boolean immediateWindowComplete;
    private int immediateChecksCompleted;
    private int immediateCheckTarget;
    private Integer frequencyMinutes;
    private LocalDateTime nextDueAt;
    private LocalDateTime overdueSince;
    private boolean overdue;

    /**
     * Where the mental-health screen stands on this plan (Tier 2 item 47).
     * Settable because the schedule is also produced by the observation
     * mapper, which knows nothing about screenings; the service stamps it.
     */
    @Setter
    private ProScreeningSummaryDTO screening;
}
