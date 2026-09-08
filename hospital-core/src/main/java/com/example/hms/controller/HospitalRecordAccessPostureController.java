package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.recordaccess.RecordAccessPostureDTO;
import com.example.hms.payload.dto.recordaccess.UpdatePostureRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.recordaccess.HospitalRecordAccessPostureService;
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

import java.util.Objects;
import java.util.UUID;

/**
 * E8 #52 — the per-hospital switch between {@code TREATMENT_PRESUMED} and
 * {@code EXPLICIT_CONSENT}. This is the legal escape hatch: if counsel or the
 * CIL rule that treatment-purpose access without authorisation is not lawful
 * for a hospital, its admin flips this and nothing else changes.
 *
 * <p>A hospital admin may set only the hospital they are acting in; a
 * super-admin may set any. Every change is a {@code CONFIGURATION_CHANGED}
 * audit row emitted by the service after commit.
 */
@Tag(name = "Record access", description = "Per-hospital record-access posture (E8 #52)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/hospitals/{hospitalId}/record-access-posture")
public class HospitalRecordAccessPostureController {

    private final HospitalRecordAccessPostureService postureService;
    private final ControllerAuthUtils authUtils;

    @Operation(summary = "Read a hospital's record-access posture")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_QUALITY_MANAGER')")
    public ResponseEntity<RecordAccessPostureDTO> get(@PathVariable UUID hospitalId) {
        return ResponseEntity.ok(postureService.get(hospitalId));
    }

    @Operation(summary = "Set a hospital's record-access posture",
        description = "TREATMENT_PRESUMED (the record follows the treatment relationship) or "
            + "EXPLICIT_CONSENT (cross-hospital reads need a patient-granted consent).")
    @WriteAudited(skip = true, reason = "service emits CONFIGURATION_CHANGED with before/after")
    @PutMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<RecordAccessPostureDTO> set(@PathVariable UUID hospitalId,
                                                      @Valid @RequestBody UpdatePostureRequestDTO body,
                                                      Authentication auth) {
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        boolean superAdmin = ctx.isSuperAdmin() || authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN");
        if (!superAdmin && !Objects.equals(ctx.getActiveHospitalId(), hospitalId)) {
            throw new AccessDeniedException("A hospital admin may only set the posture of the hospital they are acting in.");
        }
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok(postureService.set(hospitalId, body.posture(), actorUserId));
    }
}
