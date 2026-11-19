package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.imaging.pacs.PacsConnectionTestDTO;
import com.example.hms.payload.dto.imaging.pacs.PacsSettingsDTO;
import com.example.hms.payload.dto.imaging.pacs.PacsSettingsUpdateRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.imaging.PacsSettingsService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A hospital's PACS connection for the in-app image viewer (V317).
 *
 * <p>Like the record-access posture: a hospital admin reads and sets only the
 * hospital they act at, the super-admin any. The password is write-only.
 */
@Tag(name = "Imaging PACS", description = "Per-hospital PACS (DICOMweb) connection")
@RestController
@RequiredArgsConstructor
@RequestMapping("/hospitals/{hospitalId}/pacs-settings")
public class HospitalPacsSettingsController {

    private final PacsSettingsService pacsSettingsService;
    private final ControllerAuthUtils authUtils;

    @Operation(summary = "Read a hospital's PACS connection (never the password)")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PacsSettingsDTO> get(@PathVariable UUID hospitalId) {
        requireActingThere(hospitalId);
        return ResponseEntity.ok(pacsSettingsService.get(hospitalId));
    }

    @Operation(summary = "Set a hospital's PACS connection")
    @WriteAudited(skip = true, reason = "service emits CONFIGURATION_CHANGED naming the changed fields")
    @PutMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PacsSettingsDTO> set(@PathVariable UUID hospitalId,
                                               @Valid @RequestBody PacsSettingsUpdateRequestDTO body,
                                               Authentication auth) {
        requireActingThere(hospitalId);
        UUID actorUserId = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok(pacsSettingsService.set(hospitalId, body, actorUserId));
    }

    @Operation(summary = "Try the PACS connection the viewer would use for this hospital")
    @WriteAudited(skip = true, reason = "a read-only probe of the PACS; changes nothing")
    @PostMapping("/test")
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<PacsConnectionTestDTO> test(@PathVariable UUID hospitalId) {
        requireActingThere(hospitalId);
        return ResponseEntity.ok(pacsSettingsService.test(hospitalId));
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
            throw new AccessDeniedException("A hospital admin may only manage the PACS of the hospital they act at.");
        }
    }
}
