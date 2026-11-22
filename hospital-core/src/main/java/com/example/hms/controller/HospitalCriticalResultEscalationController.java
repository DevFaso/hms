package com.example.hms.controller;

import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.lab.CriticalResultEscalationSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A hospital's steps of the critical-result escalation ladder (V363; rule set CRITICAL_RESULT_ESCALATION):
 * the minutes after which the cover, then the médecin-chef and the biologist, are told, and after which the
 * result is declared unresolved; and the doctor named médecin-chef.
 *
 * <p>Like the imaging and PACS settings: a hospital admin reads and sets only the hospital they act at, the
 * super-admin any. The settings carry no result and no patient.
 */
@Tag(name = "Critical result escalation", description = "Per-hospital steps of the critical-result ladder")
@RestController
@RequiredArgsConstructor
@RequestMapping("/hospitals/{hospitalId}/critical-result-escalation")
public class HospitalCriticalResultEscalationController {

    private final CriticalResultEscalationSettingsService settingsService;

    /** The three steps are required together: an empty body changes nothing by accident. */
    public record UpdateRequest(
        @NotNull(message = "{lab.criticalEscalation.minutesRequired}") Integer coverAfterMinutes,
        @NotNull(message = "{lab.criticalEscalation.minutesRequired}") Integer leadAfterMinutes,
        @NotNull(message = "{lab.criticalEscalation.minutesRequired}") Integer unresolvedAfterMinutes,
        UUID medicalLeadUserId) {
    }

    @Operation(summary = "Read a hospital's critical-result escalation steps")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<CriticalResultEscalationSettingsService.Settings> get(@PathVariable UUID hospitalId) {
        requireActingThere(hospitalId);
        return ResponseEntity.ok(settingsService.get(hospitalId));
    }

    @Operation(summary = "Set a hospital's critical-result escalation steps")
    @PutMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<CriticalResultEscalationSettingsService.Settings> set(
            @PathVariable UUID hospitalId, @Valid @RequestBody UpdateRequest body) {
        requireActingThere(hospitalId);
        return ResponseEntity.ok(settingsService.update(hospitalId, body.coverAfterMinutes(),
            body.leadAfterMinutes(), body.unresolvedAfterMinutes(), body.medicalLeadUserId()));
    }

    /**
     * HospitalIdNarrowingInterceptor has already narrowed the request to the path's hospital or refused it; what
     * is left is that the request really acts there (the verified super-admin is Global).
     */
    private static void requireActingThere(UUID hospitalId) {
        ActingScope scope = ActingScopeResolver.currentScope();
        boolean actingThere = scope instanceof ActingScope.Global
            || (scope instanceof ActingScope.Pinned pinned && pinned.hospitalId().equals(hospitalId));
        if (!actingThere) {
            throw new AccessDeniedException(
                "A hospital admin may only manage the critical-result steps of the hospital they act at.");
        }
    }
}
