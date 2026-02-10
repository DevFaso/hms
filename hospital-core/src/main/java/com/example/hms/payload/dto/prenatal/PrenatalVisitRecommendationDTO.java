package com.example.hms.payload.dto.prenatal;

import com.example.hms.enums.PrenatalVisitType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrenatalVisitRecommendationDTO {

    private UUID appointmentId;
    private LocalDate targetDate;
    private LocalDate windowStart;
    private LocalDate windowEnd;
    private LocalTime suggestedStartTime;
    private LocalTime suggestedEndTime;
    private int gestationalWeek;
    private int durationMinutes;
    private PrenatalVisitType visitType;
    private boolean scheduled;
    private String recommendation;
    private String notes;
}
