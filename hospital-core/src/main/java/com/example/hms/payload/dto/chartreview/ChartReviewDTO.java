package com.example.hms.payload.dto.chartreview;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Aggregated read-only payload backing the Chart Review tabbed viewer
 * (Epic-style tab strip — Encounters / Notes / Results / Medications /
 * Imaging / Procedures with a unified timeline). All sections are sourced
 * from existing clinical tables; no schema changes are introduced.
 *
 * <p>Each section is capped (default 20, configurable via the
 * {@code limit} query parameter) so the response stays small on the
 * metered 3G/4G links typical of the West-Africa deployments.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Aggregated chart-review payload (encounters / notes / results / meds / imaging / procedures + timeline).")
public class ChartReviewDTO {

    private UUID patientId;
    private UUID hospitalId;
    private String hospitalName;

    /** Maximum rows requested per section (echo of the request, after clamping). */
    private int limit;

    private List<EncounterEntryDTO> encounters;
    private List<NoteEntryDTO> notes;
    private List<ResultEntryDTO> results;
    private List<MedicationEntryDTO> medications;
    private List<ImagingEntryDTO> imaging;
    private List<ProcedureEntryDTO> procedures;

    /**
     * Unified timeline merged across all six sections, sorted most-recent-first
     * and capped at {@code limit}. Lets the UI render a chronological view
     * without re-merging on the client.
     */
    private List<TimelineEventDTO> timeline;

    private LocalDateTime generatedAt;

    /* ------------------------------------------------------------------ */

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One row in the Encounters tab.")
    public static class EncounterEntryDTO {
        private UUID id;
        private String code;
        private String encounterType;
        private String status;
        private LocalDateTime encounterDate;
        private String departmentName;
        private String staffFullName;
        private String chiefComplaint;
        private String roomAssignment;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One row in the Notes tab — encounter SOAP/free-text note summary.")
    public static class NoteEntryDTO {
        private UUID id;
        private UUID encounterId;
        private String encounterCode;
        private String template;
        private String authorName;
        private String authorCredentials;
        private LocalDateTime documentedAt;
        private LocalDateTime signedAt;
        private boolean signed;
        private boolean lateEntry;
        /** Short preview (first ~280 chars) of the most-relevant body field. */
        private String preview;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One row in the Results tab — a posted lab result.")
    public static class ResultEntryDTO {
        private UUID id;
        private UUID labOrderId;
        private String testName;
        private String testCode;
        private String resultValue;
        private String resultUnit;
        private String abnormalFlag;
        private LocalDateTime resultDate;
        private String orderingStaffName;
        private boolean acknowledged;
        private boolean released;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One row in the Medications tab — a prescription.")
    public static class MedicationEntryDTO {
        private UUID id;
        private String medicationName;
        private String medicationCode;
        private String dosage;
        private String route;
        private String frequency;
        private String duration;
        private String status;
        private LocalDateTime createdAt;
        private String prescriberName;
        private boolean controlledSubstance;
        private boolean inpatientOrder;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One row in the Imaging tab — an imaging order with optional latest report summary.")
    public static class ImagingEntryDTO {
        private UUID id;
        private String modality;
        private String studyType;
        private String bodyRegion;
        private String laterality;
        private String priority;
        private String status;
        private LocalDateTime orderedAt;
        private LocalDateTime scheduledFor;
        private String clinicalQuestion;
        /** Latest report status if any (PRELIMINARY / FINAL / AMENDED). */
        private String reportStatus;
        /** Latest report impression preview (first ~280 chars). */
        private String reportImpression;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One row in the Procedures tab — a procedure order.")
    public static class ProcedureEntryDTO {
        private UUID id;
        private String procedureName;
        private String procedureCode;
        private String procedureCategory;
        private String urgency;
        private String status;
        private LocalDateTime orderedAt;
        private LocalDateTime scheduledFor;
        private String orderingProviderName;
        private String indication;
        private boolean consentObtained;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One event in the unified timeline view.")
    public static class TimelineEventDTO {
        /** Section the event came from — used as the secondary tab filter on the client. */
        public enum Section { ENCOUNTER, NOTE, RESULT, MEDICATION, IMAGING, PROCEDURE }

        private UUID id;
        private Section section;
        private LocalDateTime occurredAt;
        private String title;
        private String summary;
        private String status;
    }
}
