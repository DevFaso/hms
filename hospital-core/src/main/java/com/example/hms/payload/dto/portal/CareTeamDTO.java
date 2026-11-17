package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Aggregated care team view for the patient")
public class CareTeamDTO {

    @Schema(description = "The newest current primary care provider (one of primaryCares), kept for older clients")
    private PrimaryCareEntry primaryCare;

    @Schema(description = "Every current primary care provider, one per hospital that registers the patient, newest first")
    private List<PrimaryCareEntry> primaryCares;

    @Schema(description = "Every primary care link, current and past, newest first")
    private List<PrimaryCareEntry> primaryCareHistory;

    @Schema(description = "Clinicians of the patient's visits still open, one entry per clinician")
    private List<VisitClinicianEntry> visitClinicians;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VisitClinicianEntry {
        private UUID userId;
        private String displayName;
        private UUID hospitalId;
        private String hospitalName;
        private java.time.LocalDateTime visitDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PrimaryCareEntry {
        private UUID id;
        private UUID hospitalId;
        private String hospitalName;
        private UUID doctorUserId;
        private String doctorDisplay;
        private LocalDate startDate;
        private LocalDate endDate;
        private boolean current;
    }
}
