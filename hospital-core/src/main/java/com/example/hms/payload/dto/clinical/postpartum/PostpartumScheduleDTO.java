package com.example.hms.payload.dto.clinical.postpartum;

import com.example.hms.enums.PostpartumSchedulePhase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
}
