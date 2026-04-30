package com.example.hms.payload.dto.storyboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Aggregated patient summary used by the persistent Storyboard banner that sits
 * above every chart route. Captures the four high-impact safety items:
 * allergies, active problems, the active encounter, and code status / advance
 * directives. Designed to be small enough to fit in the patient header on
 * a low-bandwidth mobile connection.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Persistent patient banner summary (allergies / problems / active encounter / code status).")
public class PatientStoryboardDTO {

    private PatientHeaderDTO patient;
    private List<AllergySummaryDTO> allergies;
    private List<ProblemSummaryDTO> problems;
    private ActiveEncounterDTO activeEncounter;
    private CodeStatusDTO codeStatus;

    /** Convenience flags so the UI can render badges without re-deriving them. */
    private boolean hasHighSeverityAllergy;
    private boolean hasChronicProblem;

    /** Hospital that scoped the response (echo of the request). */
    private UUID hospitalId;
    private String hospitalName;

    private LocalDateTime generatedAt;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Demographics surfaced in the banner header.")
    public static class PatientHeaderDTO {
        private UUID id;
        private String mrn;
        private String firstName;
        private String lastName;
        private String fullName;
        private LocalDate dateOfBirth;
        private Integer ageYears;
        private String gender;
        private String bloodType;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Active allergy entry rendered as a chip.")
    public static class AllergySummaryDTO {
        private UUID id;
        private String allergenDisplay;
        private String allergenCode;
        private String severity;
        private String verificationStatus;
        private String reaction;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Active problem-list entry rendered as a chip.")
    public static class ProblemSummaryDTO {
        private UUID id;
        private String problemDisplay;
        private String problemCode;
        private String icdVersion;
        private String status;
        private String severity;
        private boolean chronic;
        private LocalDate onsetDate;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Most recent non-terminal encounter, if any.")
    public static class ActiveEncounterDTO {
        private UUID id;
        private String code;
        private String encounterType;
        private String status;
        private LocalDateTime encounterDate;
        private String departmentName;
        private String staffFullName;
        private String roomAssignment;
        private String chiefComplaint;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Resuscitation status and supporting advance directives.")
    public static class CodeStatusDTO {
        /** Free-text resuscitation status from {@code Patient.codeStatus} (e.g. FULL_CODE, DNR). */
        private String status;
        /** Active advance directives that justify or constrain the status. */
        private List<DirectiveSummaryDTO> directives;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Advance directive surfaced alongside the code status.")
    public static class DirectiveSummaryDTO {
        private UUID id;
        private String directiveType;
        private String status;
        private LocalDate effectiveDate;
        private LocalDate expirationDate;
        private String description;
    }
}
