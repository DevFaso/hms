package com.example.hms.payload.dto.consultation;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleConsultationRequestDTO {

    @NotNull(message = "Scheduled date/time is required")
    private LocalDateTime scheduledAt;

    private String scheduleNote;
}
