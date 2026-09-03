package com.example.hms.payload.dto.registry;

import com.example.hms.enums.CareProgram;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Enrol a patient in a care programme (Tier 2 item 35). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProgramEnrollmentRequestDTO {

    @NotNull
    private CareProgram program;

    /**
     * The clinical enrolment date. Optional; today when absent. May be in
     * the past — paper registers get backfilled — but never the future:
     * an enrolment is a fact, not an appointment.
     */
    @PastOrPresent
    private LocalDate enrolledOn;

    /**
     * Days between programme visits. Required from the clinician — the
     * server deliberately has no per-programme default to fall back on,
     * because a visit cadence is clinical protocol (see CareProgram).
     */
    @NotNull
    @Min(1)
    @Max(365)
    private Integer visitCadenceDays;

    @Size(max = 500)
    private String notes;
}
