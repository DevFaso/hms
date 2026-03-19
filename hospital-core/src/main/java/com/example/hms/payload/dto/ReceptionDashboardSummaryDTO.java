package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceptionDashboardSummaryDTO {
    private LocalDate date;
    private UUID hospitalId;
    private long scheduledToday;
    private long arrivedCount;
    private long waitingCount;
    private long inProgressCount;
    private long noShowCount;
    private long completedCount;
    private long walkInCount;
}
