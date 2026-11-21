package com.example.hms.controller;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.payload.dto.OrganisationSummaryDTO;
import com.example.hms.payload.dto.OrganisationSummaryDTO.OrganisationHospital;
import com.example.hms.service.OrganisationAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Platform admin rules 2026-11-20, rules 1a and 1b: the organisation admin's
 * own reads. No path or parameter names a hospital or an organisation: the
 * scope is the caller's ({@code UserAccountAccess.organisationHospitalIds}),
 * so there is nothing to tamper with.
 */
@Tag(name = "Organisation admin", description = "The hospitals and the totals of the caller's own organisation")
@RestController
@RequiredArgsConstructor
@RequestMapping("/organization-admin")
public class OrganisationAdminController {

    private static final String PARAM_MONTH = "month";
    /** The free-range parameters this endpoint once took, and refuses. */
    private static final java.util.Set<String> RANGE_PARAMETERS = java.util.Set.of("from", "to");

    private final OrganisationAdminService service;

    @Operation(summary = "The hospitals of the caller's organisation (for the staff registration form)")
    @GetMapping("/hospitals")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<OrganisationHospital>> hospitals() {
        return ResponseEntity.ok(service.hospitals());
    }

    @Operation(summary = "Totals per hospital of the caller's organisation, for one calendar month",
        description = "Activity counts and finance totals only: no patient, no invoice, no note. "
            + "A sensitive count from 1 to 4 is withheld. One whole month (month=yyyy-MM), or with no "
            + "month the current month to date; from/to are refused, so no range can be subtracted "
            + "from another.")
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<OrganisationSummaryDTO> summary(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam Map<String, String> parameters) {
        // Review of #1246: a from/to is refused, never silently ignored. Any other
        // parameter cannot change the period: the month is the only one read.
        if (parameters.keySet().stream().anyMatch(RANGE_PARAMETERS::contains)) {
            throw new FieldBusinessException(PARAM_MONTH, "organisation.summary.period.invalid");
        }
        return ResponseEntity.ok(service.summary(month));
    }
}
