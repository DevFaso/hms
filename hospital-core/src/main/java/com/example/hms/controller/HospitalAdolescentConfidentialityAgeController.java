package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.recordaccess.AdolescentConfidentialityAgeDTO;
import com.example.hms.payload.dto.recordaccess.UpdateAdolescentConfidentialityAgeRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.recordaccess.AdolescentConfidentialityAgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * Policy 2026-11-20 (provisional): the per-hospital adolescent
 * confidentiality age. From that age to 17 a minor's sensitive records are
 * hidden from the parents unless a clinician releases them. The default (12)
 * is borrowed from international practice and awaits a jurist.
 *
 * <p>A hospital admin may set only the hospital they are acting in; a
 * super-admin may set any. Every change is a {@code CONFIGURATION_CHANGED}
 * audit row emitted by the service after commit.
 */
@Tag(name = "Record access", description = "Per-hospital adolescent confidentiality age (provisional)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/hospitals/{hospitalId}/adolescent-confidentiality-age")
public class HospitalAdolescentConfidentialityAgeController {

    private final AdolescentConfidentialityAgeService ageService;
    private final ControllerAuthUtils authUtils;

    @Operation(summary = "Read a hospital's adolescent confidentiality age")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_QUALITY_MANAGER')")
    public ResponseEntity<AdolescentConfidentialityAgeDTO> get(@PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ageService.get(hospitalId));
    }

    @Operation(summary = "Set a hospital's adolescent confidentiality age",
        description = "An age from 0 to 18; null returns the hospital to the provisional default (12).")
    @WriteAudited(skip = true, reason = "service emits CONFIGURATION_CHANGED with before/after")
    @PutMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<AdolescentConfidentialityAgeDTO> set(
            @PathVariable UUID hospitalId,
            @Valid @RequestBody UpdateAdolescentConfidentialityAgeRequestDTO body,
            Authentication auth) {
        // As the record-access posture: HospitalIdNarrowingInterceptor narrowed the request to this
        // path's hospital or refused it; what is left is that the request really acts at it.
        ActingScope scope = ActingScopeResolver.currentScope();
        boolean actingThere = scope instanceof ActingScope.Global
            || (scope instanceof ActingScope.Pinned pinned && pinned.hospitalId().equals(hospitalId));
        if (!actingThere) {
            throw new AccessDeniedException("A hospital admin may only set this for the hospital they are acting in.");
        }
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok(ageService.set(hospitalId, body.age(), actorUserId));
    }
}
