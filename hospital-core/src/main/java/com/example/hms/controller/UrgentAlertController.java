package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.clinical.UrgentAlertIncidentDTO;
import com.example.hms.payload.dto.clinical.UrgentAlertPatientMarkDTO;
import com.example.hms.payload.dto.clinical.UrgentAlertsMineDTO;
import com.example.hms.service.escalation.UrgentAlertOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * QA D4100, D4101, D4102 (r81): the urgent alerts the escalation ladder is waiting on. Read-only, and always of
 * the caller's acting hospital: no hospital id is taken from the request.
 */
@RestController
@RequestMapping(value = "/urgent-alerts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Urgent alerts", description = "The urgent clinical alerts waiting for an acknowledgement")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class UrgentAlertController {

    /** The roles an urgent alert of the ladder is filed to: each reads its own alerts and the patients' marks. */
    static final String CLINICIANS = "hasAnyAuthority('ROLE_DOCTOR','ROLE_PHYSICIAN','ROLE_SURGEON','ROLE_NURSE',"
        + "'ROLE_MIDWIFE','ROLE_LAB_DIRECTOR')";
    /** The register: the hospital administrator, and the doctors (the service keeps the médecin-chef only). */
    static final String REGISTER_READERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_PHYSICIAN',"
        + "'ROLE_SURGEON')";
    private static final List<String> DOCTOR_ROLES = List.of("ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON");

    private final UrgentAlertOverviewService overview;
    private final ControllerAuthUtils authUtils;

    @GetMapping(value = "/mine", consumes = MediaType.ALL_VALUE)
    @PreAuthorize(CLINICIANS)
    @Operation(summary = "The caller's urgent alerts to acknowledge, and those the caller documented that still wait")
    public ResponseEntity<UrgentAlertsMineDTO> mine(Authentication auth) {
        return ResponseEntity.ok(overview.mine(requireUserId(auth), requireHospital(auth)));
    }

    @GetMapping(value = "/patients", consumes = MediaType.ALL_VALUE)
    @PreAuthorize(CLINICIANS)
    @Operation(summary = "Per patient of the hospital: the urgent alerts nobody has acknowledged, and the oldest")
    public ResponseEntity<List<UrgentAlertPatientMarkDTO>> patients(Authentication auth) {
        requireUserId(auth);
        return ResponseEntity.ok(overview.patientMarks(requireHospital(auth)));
    }

    @GetMapping(value = "/incidents", consumes = MediaType.ALL_VALUE)
    @PreAuthorize(REGISTER_READERS)
    @Operation(summary = "The register of unresolved urgent alerts of the hospital")
    public ResponseEntity<List<UrgentAlertIncidentDTO>> incidents(Authentication auth) {
        boolean administrator = authUtils.hasAuthority(auth, "ROLE_HOSPITAL_ADMIN");
        boolean doctor = DOCTOR_ROLES.stream().anyMatch(role -> authUtils.hasAuthority(auth, role));
        return ResponseEntity.ok(overview.incidents(requireUserId(auth), requireHospital(auth), administrator, doctor));
    }

    private UUID requireUserId(Authentication auth) {
        authUtils.requireAuth(auth);
        return authUtils.resolveUserId(auth)
            .orElseThrow(() -> new BusinessException("request.userContext.unavailable"));
    }

    /** The acting hospital; a caller with none (a global view, an ambiguous scope) reads nothing here. */
    private UUID requireHospital(Authentication auth) {
        UUID hospitalId = authUtils.resolveHospitalScope(auth, null, null, false);
        if (hospitalId == null) {
            throw new BusinessException("request.hospitalContext.required");
        }
        return hospitalId;
    }
}
