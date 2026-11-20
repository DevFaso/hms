package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.imaging.ImagingReportingSettingsDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.imaging.ImagingReportingSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Policy rule 3 (owner 2026-11-20, provisional): a hospital's imaging reporting settings (V346).
 *
 * <p>Like the PACS connection: a hospital admin reads and sets only the hospital they act at, the
 * super-admin any. One setting today, whether an imaging technician may perform an ultrasound;
 * it defaults to physicians only. The report stays a physician's either way (pending the
 * ministry's position).
 */
@Tag(name = "Imaging reporting settings", description = "Per-hospital imaging reporting settings")
@RestController
@RequiredArgsConstructor
@RequestMapping("/hospitals/{hospitalId}/imaging-reporting-settings")
public class HospitalImagingReportingSettingsController {

    private final ImagingReportingSettingsService settingsService;
    private final ControllerAuthUtils authUtils;

    /** What the admin sets; the switch is required so an empty body changes nothing by accident. */
    public record UpdateRequest(
        @NotNull(message = "{imaging.reportingSettings.ultrasound.required}") Boolean ultrasoundByNonPhysician) {
    }

    @Operation(summary = "Read a hospital's imaging reporting settings")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ImagingReportingSettingsDTO> get(@PathVariable UUID hospitalId) {
        requireActingThere(hospitalId);
        return ResponseEntity.ok(settingsService.get(hospitalId));
    }

    @Operation(summary = "Set a hospital's imaging reporting settings")
    @WriteAudited(skip = true, reason = "service emits CONFIGURATION_CHANGED with the new value")
    @PutMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ImagingReportingSettingsDTO> set(@PathVariable UUID hospitalId,
                                                           @Valid @RequestBody UpdateRequest body,
                                                           Authentication auth) {
        requireActingThere(hospitalId);
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok(settingsService.setUltrasoundByNonPhysician(hospitalId,
            Boolean.TRUE.equals(body.ultrasoundByNonPhysician()), actorUserId));
    }

    /**
     * HospitalIdNarrowingInterceptor has already narrowed the request to the path's hospital or
     * refused it; what is left is that the request really acts there (the verified super-admin is
     * Global).
     */
    private static void requireActingThere(UUID hospitalId) {
        ActingScope scope = ActingScopeResolver.currentScope();
        boolean actingThere = scope instanceof ActingScope.Global
            || (scope instanceof ActingScope.Pinned pinned && pinned.hospitalId().equals(hospitalId));
        if (!actingThere) {
            throw new AccessDeniedException(
                "A hospital admin may only manage the imaging settings of the hospital they act at.");
        }
    }
}
