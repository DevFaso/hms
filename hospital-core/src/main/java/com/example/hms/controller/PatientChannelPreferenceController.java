package com.example.hms.controller;

import com.example.hms.security.chart.RestrictedChartPolicy;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.notification.PatientChannelPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * QA D2203: the patient's SMS and e-mail choices, per kind of notice, seen and recorded at the desk (the same
 * preferences the senders honour). The front desk and the hospital admin of a hospital where the patient is
 * registered; never the super-admin (the {@code PUT /patients/**} matcher refuses it before this annotation).
 */
@RestController
@RequestMapping("/patients/{patientId}/channel-preferences")
@RequiredArgsConstructor
@Tag(name = "Patient channel choices", description = "SMS and e-mail choices recorded at the desk")
@RestrictedChartPolicy(value = RestrictedChartPolicy.Mode.NONE,
    reason = "how the patient is contacted: administrative")
public class PatientChannelPreferenceController {

    private static final String ROLES = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST')";

    private final PatientChannelPreferenceService service;
    private final ControllerAuthUtils authUtils;

    @GetMapping
    @PreAuthorize(ROLES)
    @Operation(summary = "The patient's SMS and e-mail choices (on unless turned off)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<NotificationPreferenceDTO>> read(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        return ResponseEntity.ok(service.read(patientId, scope));
    }

    @PutMapping
    @PreAuthorize(ROLES)
    @WriteAudited(skip = true, reason = "service emits PATIENT_CHANNEL_PREFERENCES_UPDATED with the changes")
    @Operation(summary = "Record the patient's SMS and e-mail choices (audited)",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<List<NotificationPreferenceDTO>> update(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @Valid @RequestBody List<@Valid NotificationPreferenceUpdateDTO> updates,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID scope = authUtils.resolveHospitalScope(auth, hospitalId, null, true);
        UUID actor = authUtils.resolveUserId(auth).orElse(null);
        return ResponseEntity.ok(service.update(patientId, scope, updates, actor));
    }
}
