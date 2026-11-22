package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.AssignmentActivationCodeDTO;
import com.example.hms.payload.dto.NotificationDeliveryStatusDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.UserRoleHospitalAssignmentService;
import com.example.hms.utility.ActivationDeliveryTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * QA r70 D3206: the desk that registered a patient finishes the patient's portal account when the activation SMS
 * went nowhere (always on dev, an SMS failure or a phone without credit in production). The receptionist is the
 * patient's registrar, yet could not see the code: {@code /assignments} is the administrators' surface.
 *
 * <p>Same pattern as the staff registrar's code (#1246, {@code POST /assignments/{id}/activation-code}): a POST, so
 * no cache or proxy keeps it, answered {@code no-store}; only while the invitation is pending and its code
 * unexpired; the reveal and the re-send are audited (DATA_ACCESS) and the code is never logged. Scope: the PATIENT
 * invitation at the hospital the request acts at, by a receptionist or hospital administrator OF that hospital
 * (checked in the service on the request's own roles).
 */
@Slf4j
@RestController
@RequestMapping("/patients/{patientId}/portal-activation")
@RequiredArgsConstructor
@Tag(name = "Patient portal activation", description = "The desk hands over or re-sends a patient's activation code")
public class PatientPortalActivationController {

    private static final String DESK = "hasAnyAuthority('ROLE_RECEPTIONIST','ROLE_HOSPITAL_ADMIN')";

    private final UserRoleHospitalAssignmentService assignmentService;
    private final ControllerAuthUtils authUtils;

    @WriteAudited(skip = true, reason = "service emits DATA_ACCESS for the reveal, without the code")
    @Operation(summary = "Show the desk a patient's pending portal activation code",
        description = "For handing the code over in person. Only a pending, unexpired invitation at the acting "
            + "hospital; audited without the code.",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize(DESK)
    @PostMapping("/code")
    public ResponseEntity<AssignmentActivationCodeDTO> revealCode(@PathVariable UUID patientId, Authentication auth) {
        authUtils.requireAuth(auth);
        UUID hospitalId = authUtils.resolveHospitalScope(auth, null, true);
        // The code is never logged: only that it was asked for.
        log.info("🔑 Patient portal activation code asked for patient '{}'", patientId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(assignmentService.revealPatientActivationCode(patientId, hospitalId));
    }

    @WriteAudited(skip = true, reason = "service emits DATA_ACCESS for the re-send")
    @Operation(summary = "Re-send a patient's portal activation (e-mail and SMS)",
        description = "An expired code is replaced first. Returns the per-channel delivery report.",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize(DESK)
    @PostMapping("/resend")
    public ResponseEntity<List<NotificationDeliveryStatusDTO>> resend(@PathVariable UUID patientId,
                                                                      Authentication auth) {
        authUtils.requireAuth(auth);
        UUID hospitalId = authUtils.resolveHospitalScope(auth, null, true);
        log.info("📧 Patient portal activation re-send asked for patient '{}'", patientId);
        ActivationDeliveryTracker.open();
        try {
            assignmentService.resendPatientActivation(patientId, hospitalId);
            return ResponseEntity.ok(ActivationDeliveryTracker.close());
        } finally {
            ActivationDeliveryTracker.close();
        }
    }
}
