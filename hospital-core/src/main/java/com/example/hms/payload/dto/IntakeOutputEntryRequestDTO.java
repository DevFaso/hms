package com.example.hms.payload.dto;

import com.example.hms.enums.IntakeOutputRoute;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One fluid intake/output measurement. The client sends only the route —
 * the category (INTAKE vs OUTPUT) is derived from it server-side, so a
 * mismatched pair cannot be expressed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntakeOutputEntryRequestDTO {

    @NotNull
    private IntakeOutputRoute route;

    @NotNull
    @Min(1)
    @Max(10000)
    private Integer volumeMl;

    /** When the fluid actually moved; defaults to now when omitted. */
    @PastOrPresent
    private LocalDateTime observationTime;

    private Boolean lateEntry;

    @Size(max = 500)
    private String notes;
}
