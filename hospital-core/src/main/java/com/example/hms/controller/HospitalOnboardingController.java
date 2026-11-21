package com.example.hms.controller;

import com.example.hms.payload.dto.HospitalOnboardingReadinessDTO;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.HospitalOnboardingReadinessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * QA r65 D2731: the onboarding checklist of a hospital (what it still needs
 * before patients can book online).
 *
 * <p>Like the PACS connection and the record-access posture: a hospital admin
 * reads only the hospital they act at, the super-admin any.
 */
@Tag(name = "Hospital onboarding", description = "What a new hospital still needs before it can work")
@RestController
@RequiredArgsConstructor
@RequestMapping("/hospitals/{hospitalId}/onboarding-readiness")
public class HospitalOnboardingController {

    private final HospitalOnboardingReadinessService readinessService;

    @Operation(summary = "The onboarding checklist of a hospital, and whether patients can book online yet")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<HospitalOnboardingReadinessDTO> get(@PathVariable UUID hospitalId) {
        requireActingThere(hospitalId);
        return ResponseEntity.ok(readinessService.readiness(hospitalId));
    }

    /**
     * HospitalIdNarrowingInterceptor has already narrowed the request to the
     * path's hospital or refused it; what is left is that the request really
     * acts there (the verified super-admin is Global).
     */
    private static void requireActingThere(UUID hospitalId) {
        ActingScope scope = ActingScopeResolver.currentScope();
        boolean actingThere = scope instanceof ActingScope.Global
            || (scope instanceof ActingScope.Pinned pinned && pinned.hospitalId().equals(hospitalId));
        if (!actingThere) {
            throw new AccessDeniedException("A hospital admin may only read the checklist of the hospital they act at.");
        }
    }
}
