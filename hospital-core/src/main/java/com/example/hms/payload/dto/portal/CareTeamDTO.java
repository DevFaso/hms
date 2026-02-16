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

    @Schema(description = "Current primary care provider")
    private PrimaryCareEntry primaryCare;

    @Schema(description = "Historical primary care providers")
    private List<PrimaryCareEntry> primaryCareHistory;

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
