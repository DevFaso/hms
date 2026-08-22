package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.service.PatientPhotoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Patient photo capture (P3 #21).
 *
 * <p>The binary is streamed HERE, authenticated — never through the
 * permitAll {@code GET /uploads/**} static handler (a patient photo is
 * PHI). NOTE on the filter chain: GET rides the broad
 * {@code GET /patients/**} matcher, so the annotation is the authoritative
 * per-role gate; POST/DELETE subpaths under /patients match NO explicit
 * matcher and fall to {@code anyRequest().authenticated()}, so the
 * annotations below are the ONLY gate there — deliberate and documented,
 * the house pattern for /patients subresources.
 */
@RestController
@RequestMapping("/patients/{patientId}/photo")
@RequiredArgsConstructor
@Tag(name = "Patient Photo", description = "Authenticated patient photo capture and streaming")
public class PatientPhotoController {

    private static final String WRITE_ROLES =
        "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE',"
            + "'ROLE_DOCTOR','ROLE_SUPER_ADMIN')";

    private static final String READ_ROLES =
        "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST','ROLE_NURSE','ROLE_MIDWIFE',"
            + "'ROLE_DOCTOR','ROLE_PHARMACIST','ROLE_SUPER_ADMIN')";

    private final PatientPhotoService patientPhotoService;
    private final ControllerAuthUtils authUtils;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Upload or replace the patient photo",
        description = "Max 5 MB, image types only. Replacing deletes the previous file.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Map<String, LocalDateTime>> upload(
        @PathVariable UUID patientId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        LocalDateTime updatedAt = patientPhotoService.upload(patientId, scope, file);
        return ResponseEntity.ok(Map.of("photoUpdatedAt", updatedAt));
    }

    @GetMapping
    @PreAuthorize(READ_ROLES)
    @Operation(summary = "Stream the patient photo (authenticated)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<byte[]> get(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, false);
        PatientPhotoService.PhotoPayload payload = patientPhotoService.load(patientId, scope);
        return ResponseEntity.ok()
            // Private: browsers may keep it, shared proxies must not.
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(payload.contentType()))
            .body(payload.bytes());
    }

    @DeleteMapping
    @PreAuthorize(WRITE_ROLES)
    @Operation(summary = "Remove the patient photo",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> delete(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        patientPhotoService.delete(patientId, scope);
        return ResponseEntity.noContent().build();
    }
}
