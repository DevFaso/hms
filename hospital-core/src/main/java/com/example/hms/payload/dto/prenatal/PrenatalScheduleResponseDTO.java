package com.example.hms.payload.dto.prenatal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrenatalScheduleResponseDTO {
    private UUID patientId;
    private UUID hospitalId;
    private UUID staffId;
    private LocalDate estimatedDueDate;
    private int currentGestationalWeek;
    private boolean highRisk;
    private List<PrenatalVisitRecommendationDTO> recommendations;
    private List<PrenatalAppointmentSummaryDTO> existingAppointments;
    private List<String> alerts;
}
