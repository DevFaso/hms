package com.example.hms.controller;

import com.example.hms.payload.dto.analytics.MorbidityDashboardDTO;
import com.example.hms.service.analytics.MorbidityAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * Morbidity surveillance — which diagnoses were recorded most in a month.
 *
 * <p>Aggregate-only: counts per ICD code, never patient rows, so the read
 * needs no {@code PATIENT_ACCESS} audit emission (the same reasoning as
 * {@link KpiDashboardController}).
 *
 * <p>Scope is decided by the SERVICE from the caller's own authorities —
 * there is no {@code hospitalId} parameter to tamper with. A SUPER_ADMIN
 * sees every hospital and the per-hospital split; a hospital admin sees
 * only their own active hospital.
 */
@RestController
@RequestMapping(value = "/morbidity", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Morbidity dashboard", description = "Top diagnoses per month, aggregate-only")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class MorbidityDashboardController {

    /** Ranked rows returned per scope. Enough for a readable chart, not a data dump. */
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 25;

    /**
     * How far back a month may be requested. Generous enough for
     * year-on-year comparison, bounded so the query cannot be aimed at an
     * arbitrary point in history.
     */
    private static final int MAX_MONTHS_BACK = 36;

    private final MorbidityAnalyticsService morbidityAnalyticsService;

    @GetMapping("/top-diagnoses")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    @Operation(summary = "Top diagnoses recorded in one month",
        description = "Ranked counts for the caller's own hospital, or network-wide with a "
            + "per-hospital breakdown for SUPER_ADMIN. Defaults to the current month.")
    public ResponseEntity<MorbidityDashboardDTO> topDiagnoses(
        @RequestParam(value = "month", required = false) String month,
        @RequestParam(value = "limit", required = false, defaultValue = "" + DEFAULT_LIMIT)
        int limit
    ) {
        YearMonth target;
        try {
            target = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        } catch (DateTimeParseException ex) {
            // A malformed month is a refusal, not a silent fallback to "now" —
            // a chart quietly showing a different period than the one asked
            // for is worse than an error.
            return ResponseEntity.badRequest().build();
        }
        if (target.isAfter(YearMonth.now())
            || target.isBefore(YearMonth.now().minusMonths(MAX_MONTHS_BACK))) {
            return ResponseEntity.badRequest().build();
        }
        int capped = Math.clamp(limit, 1, MAX_LIMIT);
        return ResponseEntity.ok(morbidityAnalyticsService.topDiagnoses(target, capped));
    }
}
