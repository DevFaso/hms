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
 * <p>Each section answers one page (default 20 rows, configurable via the
 * {@code limit} query parameter) so the response stays small on the
 * metered 3G/4G links typical of the West-Africa deployments. QA D1380
 * (D1347 class): the page is never presented as the whole section —
 * {@link #totals} carries the server's count of every row of each section,
 * and {@code page} / {@code section} read the rest.
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

    /** Rows per section page (echo of the request, after clamping). */
    private int limit;

    /** QA D1380: the 0-based page of each section returned. */
    private int page;

    /** QA D1380: the one section asked for, or {@code null} when every section's page is returned. */
    private String section;

    /** QA D1380: the server's count of every row of each section (never the page's length). */
    private Totals totals;

    /**
     * QA D1380: the encounter, note and imaging rows the timeline page names,
     * so a timeline page past the first labels its rows from them (QA D829)
     * even when they are not on the current page of their own tab.
     */
    private TimelineRefs timelineRefs;

    private List<EncounterEntryDTO> encounters;
    private List<NoteEntryDTO> notes;
    private List<ResultEntryDTO> results;
    private List<MedicationEntryDTO> medications;
    private List<ImagingEntryDTO> imaging;
    private List<ProcedureEntryDTO> procedures;

    /**
     * Unified timeline merged across all six sections, sorted most-recent-first;
     * one page of {@code limit} events ({@link Totals#getTimeline()} counts them all). Lets the UI render a chronological view
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
    @Schema(description = "QA D1380: the count of every row of each chart-review section.")
    public static class Totals {
        private int timeline;
        private int encounters;
        private int notes;
        private int results;
        private int medications;
        private int imaging;
        private int procedures;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "QA D1380: the rows the timeline page refers to, for its labels.")
    public static class TimelineRefs {
        private List<EncounterEntryDTO> encounters;
        private List<NoteEntryDTO> notes;
        private List<ImagingEntryDTO> imaging;
    }

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
        /** QA D1107: the title the author gave the note (a nursing note's), when there is one. */
        private String title;
        private String authorName;
        private String authorCredentials;
        private LocalDateTime documentedAt;
        private LocalDateTime signedAt;
        private boolean signed;
        private boolean lateEntry;
        /* Co-sign state (P3 #20): pending = requiresCosign && cosignedAt null. */
        private boolean requiresCosign;
        private LocalDateTime cosignedAt;
        /** Short preview (first ~280 chars) of the most-relevant body field. */
        private String preview;
        /** QA D1076: {@code ENCOUNTER} (the visit's clinical note) or {@code NURSING} (a ward nursing note). */
        private String source;
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
        /** NORMAL / ABNORMAL / CRITICAL — the family the tab and the timeline pill colour on. */
        private String abnormalFlag;
        /** LOW / HIGH when the abnormal result's side of the range is known. */
        private com.example.hms.enums.AbnormalDirection abnormalDirection;
        private LocalDateTime resultDate;
        private String orderingStaffName;
        private boolean acknowledged;
        private boolean released;
        /** QA D1016: the result corrects a released value the reader may already have acted on. */
        private boolean corrected;
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
        /** QA D1016/D931 (round 18): a RESULT event whose value replaces a released one. */
        private boolean corrected;
    }
}
