package com.example.hms.payload.dto.referral;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralStatusSummaryDTO {
    private long submitted;
    private long acknowledged;
    private long inProgress;
    private long completed;
    private long cancelled;
    private long overdue;
}
