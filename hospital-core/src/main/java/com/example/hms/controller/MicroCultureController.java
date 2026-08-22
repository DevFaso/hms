package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.payload.dto.MicroCultureRequestDTO;
import com.example.hms.payload.dto.MicroCultureResponseDTO;
import com.example.hms.payload.dto.MicroCultureUpdateDTO;
import com.example.hms.payload.dto.MicroIsolateRequestDTO;
import com.example.hms.payload.dto.MicroSusceptibilityRequestDTO;
import com.example.hms.service.MicroCultureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Microbiology culture reports (P3 #19).
 *
 * <p>Deliberately a NEW url root: nesting writes under {@code /lab-orders/**}
 * would put them behind the POST /lab-orders/** SecurityConfig matcher, which
 * admits only clinical roles and 403s every lab role before {@code @PreAuthorize}
 * runs (first-match-wins; the transition/specimen endpoints already suffer
 * this). {@code /micro-cultures} has no filter-chain matcher, so it rides
 * {@code anyRequest().authenticated()} and the annotations here are the
 * authoritative gate.
 *
 * <p>Result-entry roles mirror POST /lab-results; finalize narrows to the lab
 * scientist tier, mirroring the order state machine's VERIFIED transition.
 */
@RestController
@RequestMapping("/micro-cultures")
@RequiredArgsConstructor
@Tag(name = "Microbiology", description = "Culture reports, isolates and susceptibility panels")
public class MicroCultureController {

    private static final String ENTRY_ROLES =
        "hasAnyAuthority('ROLE_LAB_SCIENTIST','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER','ROLE_LAB_DIRECTOR',"
            + "'ROLE_QUALITY_MANAGER','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_SUPER_ADMIN')";

    private static final String FINALIZE_ROLES =
        "hasAnyAuthority('ROLE_LAB_SCIENTIST','ROLE_LAB_MANAGER','ROLE_LAB_DIRECTOR','ROLE_SUPER_ADMIN')";

    private static final String READ_ROLES =
        "hasAnyAuthority('ROLE_LAB_SCIENTIST','ROLE_LAB_TECHNICIAN','ROLE_LAB_MANAGER','ROLE_LAB_DIRECTOR',"
            + "'ROLE_QUALITY_MANAGER','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_HOSPITAL_ADMIN',"
            + "'ROLE_PHARMACIST','ROLE_SUPER_ADMIN')";

    private final MicroCultureService microCultureService;
    private final ControllerAuthUtils authUtils;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ENTRY_ROLES)
    @Operation(summary = "Create a culture report on a lab order",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MicroCultureResponseDTO> createCulture(
        @Valid @RequestBody MicroCultureRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(microCultureService.createCulture(scope, actorUserId, request));
    }

    @GetMapping
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "List culture reports for the caller's hospital",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Page<MicroCultureResponseDTO>> listCultures(
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) MicroCultureStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.ok(microCultureService.getForHospital(
            scope, status, PageRequest.of(page, Math.min(size, 200))));
    }

    @GetMapping("/{cultureId}")
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "One culture report with isolates and susceptibilities",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MicroCultureResponseDTO> getCulture(
        @PathVariable UUID cultureId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        return ResponseEntity.ok(microCultureService.getCulture(cultureId, scope));
    }

    @PutMapping("/{cultureId}")
    @PreAuthorize(ENTRY_ROLES)
    @Operation(summary = "Update culture-level fields",
        description = "After FINAL, a correctionReason is mandatory and the report becomes CORRECTED.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MicroCultureResponseDTO> updateCulture(
        @PathVariable UUID cultureId,
        @Valid @RequestBody MicroCultureUpdateDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.ok(microCultureService.updateCulture(cultureId, scope, request));
    }

    @PostMapping("/{cultureId}/finalize")
    @PreAuthorize(FINALIZE_ROLES)
    @Operation(summary = "Finalize a preliminary culture report",
        description = "Requires a growth result; a growth report needs at least one isolate. "
            + "A finalized growth report notifies the ordering provider (best effort).",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MicroCultureResponseDTO> finalizeCulture(
        @PathVariable UUID cultureId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok(microCultureService.finalizeCulture(cultureId, scope, actorUserId));
    }

    @PostMapping("/{cultureId}/isolates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ENTRY_ROLES)
    @Operation(summary = "Add an organism to a culture report",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MicroCultureResponseDTO> addIsolate(
        @PathVariable UUID cultureId,
        @Valid @RequestBody MicroIsolateRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(microCultureService.addIsolate(cultureId, scope, request));
    }

    @PutMapping("/{cultureId}/isolates/{isolateId}")
    @PreAuthorize(ENTRY_ROLES)
    @Operation(summary = "Update an isolate",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MicroCultureResponseDTO> updateIsolate(
        @PathVariable UUID cultureId,
        @PathVariable UUID isolateId,
        @Valid @RequestBody MicroIsolateRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.ok(microCultureService.updateIsolate(cultureId, isolateId, scope, request));
    }

    @DeleteMapping("/{cultureId}/isolates/{isolateId}")
    @PreAuthorize(ENTRY_ROLES)
    @Operation(summary = "Remove an isolate (and its susceptibility rows)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MicroCultureResponseDTO> deleteIsolate(
        @PathVariable UUID cultureId,
        @PathVariable UUID isolateId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) String correctionReason,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.ok(
            microCultureService.deleteIsolate(cultureId, isolateId, scope, correctionReason));
    }

    @PostMapping("/{cultureId}/isolates/{isolateId}/susceptibilities")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ENTRY_ROLES)
    @Operation(summary = "Add an antibiotic susceptibility row to an isolate",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MicroCultureResponseDTO> addSusceptibility(
        @PathVariable UUID cultureId,
        @PathVariable UUID isolateId,
        @Valid @RequestBody MicroSusceptibilityRequestDTO request,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(microCultureService.addSusceptibility(cultureId, isolateId, scope, request));
    }

    @DeleteMapping("/{cultureId}/isolates/{isolateId}/susceptibilities/{susceptibilityId}")
    @PreAuthorize(ENTRY_ROLES)
    @Operation(summary = "Remove a susceptibility row",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MicroCultureResponseDTO> deleteSusceptibility(
        @PathVariable UUID cultureId,
        @PathVariable UUID isolateId,
        @PathVariable UUID susceptibilityId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestParam(required = false) String correctionReason,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.ok(microCultureService.deleteSusceptibility(
            cultureId, isolateId, susceptibilityId, scope, correctionReason));
    }
}
