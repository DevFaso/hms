package com.example.hms.payload.dto.consultation;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class ConsultationStatsDTO {

    private long total;
    private long requested;
    private long active;
    private long completed;
    private long cancelled;
    private long declined;
    private long overdue;
    private double avgHoursToAssign;
    private double avgHoursToComplete;
    private Map<String, Long> bySpecialty;
}
