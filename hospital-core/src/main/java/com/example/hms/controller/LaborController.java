package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO;
import com.example.hms.payload.dto.clinical.labor.DeliveryRecordResponseDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeRequestDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeResponseDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryRequestDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryResponseDTO;
import com.example.hms.service.LaborService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Labor & Delivery (P1 #6): labor episodes, WHO partograph entries, delivery
 * records. Patient-nested like the postpartum/newborn controllers; the same
 * clinical role set gates every route.
 */
@RestController
@RequestMapping("/patients/{patientId}/labor")
@RequiredArgsConstructor
@Tag(name = "Labor & Delivery", description = "Labor episodes, WHO partograph capture, and delivery records")
public class LaborController {

    private static final String CLINICAL_ROLES =
        "hasAnyAuthority('ROLE_NURSE','ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')";

    private static final int DEFAULT_EPISODE_LIMIT = 10;
    private static final int MAX_EPISODE_LIMIT = 50;

    private final LaborService laborService;
    private final ControllerAuthUtils authUtils;

    @PostMapping("/episodes")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Admit a patient in labor (start an episode)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<LaborEpisodeResponseDTO> startEpisode(
        @PathVariable UUID patientId,
        @Valid @RequestBody LaborEpisodeRequestDTO request,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID recorderUserId = authUtils.resolveUserId(auth).orElse(null);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, request.getHospitalId(), true);
        if (resolvedHospitalId != null) {
            request.setHospitalId(resolvedHospitalId);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(laborService.startEpisode(patientId, recorderUserId, request));
    }

    @GetMapping("/episodes")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "List the patient's labor episodes, newest first",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<LaborEpisodeResponseDTO>> getEpisodes(
        @PathVariable UUID patientId,
        @RequestParam(name = "limit", required = false, defaultValue = "10") int limit,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        int effectiveLimit = authUtils.sanitizeLimit(limit, DEFAULT_EPISODE_LIMIT, MAX_EPISODE_LIMIT);
        return ResponseEntity.ok(laborService.getEpisodes(patientId, resolvedHospitalId, effectiveLimit));
    }

    @PostMapping("/episodes/{episodeId}/entries")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Record a WHO partograph timepoint",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<PartographEntryResponseDTO> addEntry(
        @PathVariable UUID patientId,
        @PathVariable UUID episodeId,
        @Valid @RequestBody PartographEntryRequestDTO request,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID recorderUserId = authUtils.resolveUserId(auth).orElse(null);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, request.getHospitalId(), true);
        if (resolvedHospitalId != null) {
            request.setHospitalId(resolvedHospitalId);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(laborService.addEntry(patientId, episodeId, recorderUserId, request));
    }

    @GetMapping("/episodes/{episodeId}/entries")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "List an episode's partograph timepoints, oldest first",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<PartographEntryResponseDTO>> getEntries(
        @PathVariable UUID patientId,
        @PathVariable UUID episodeId,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        return ResponseEntity.ok(laborService.getEntries(patientId, episodeId, resolvedHospitalId));
    }

    @PostMapping("/episodes/{episodeId}/delivery")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "File the delivery record and close the episode",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<DeliveryRecordResponseDTO> recordDelivery(
        @PathVariable UUID patientId,
        @PathVariable UUID episodeId,
        @Valid @RequestBody DeliveryRecordRequestDTO request,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID recorderUserId = authUtils.resolveUserId(auth).orElse(null);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, request.getHospitalId(), true);
        if (resolvedHospitalId != null) {
            request.setHospitalId(resolvedHospitalId);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(laborService.recordDelivery(patientId, episodeId, recorderUserId, request));
    }

    @GetMapping("/episodes/{episodeId}/delivery")
    @PreAuthorize(CLINICAL_ROLES)
    @Operation(summary = "Fetch an episode's delivery record",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<DeliveryRecordResponseDTO> getDelivery(
        @PathVariable UUID patientId,
        @PathVariable UUID episodeId,
        @RequestParam(name = "hospitalId", required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospitalId = authUtils.resolveHospitalScope(auth, hospitalId, false);
        return ResponseEntity.ok(laborService.getDelivery(patientId, episodeId, resolvedHospitalId));
    }
}
