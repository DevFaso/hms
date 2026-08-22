package com.example.hms.payload.dto;

import com.example.hms.enums.IntakeOutputCategory;
import com.example.hms.enums.IntakeOutputRoute;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The fluid-balance view for one patient and window: every entry plus the
 * server-computed totals, so no client ever re-derives the balance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntakeOutputSummaryDTO {

    private UUID patientId;
    private LocalDateTime windowFrom;
    private LocalDateTime windowTo;
    private long totalIntakeMl;
    private long totalOutputMl;
    /** intake minus output over the window. */
    private long balanceMl;
    private List<Entry> entries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private UUID id;
        private LocalDateTime observationTime;
        private LocalDateTime documentedAt;
        private boolean lateEntry;
        private IntakeOutputCategory category;
        private IntakeOutputRoute route;
        private Integer volumeMl;
        private String notes;
        private String recordedByName;
    }
}
