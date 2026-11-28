package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.clinical.UrgentAlertIncidentDTO;
import com.example.hms.payload.dto.clinical.UrgentAlertPatientMarkDTO;
import com.example.hms.payload.dto.clinical.UrgentAlertsMineDTO;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.service.escalation.UrgentAlertOverviewService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
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
    static final String CLINICIAN_REFUSAL_KEY = "urgentAlert.readers";
    static final String REGISTER_READERS = "hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_DOCTOR','ROLE_PHYSICIAN',"
        + "'ROLE_SURGEON')";
    private final UrgentAlertOverviewService overview;
    private final ControllerAuthUtils authUtils;
    private final RoleValidator roleValidator;

    @GetMapping(value = "/mine", consumes = MediaType.ALL_VALUE)
    @PreAuthorize(CLINICIANS)
    @Operation(summary = "The caller's urgent alerts to acknowledge, and those the caller documented that still wait")
    public ResponseEntity<UrgentAlertsMineDTO> mine(Authentication auth) {
        UUID userId = requireUserId(auth);
        UUID hospitalId = requireHospital(auth);
        requireClinicianAt(userId, hospitalId);
        return ResponseEntity.ok(overview.mine(userId, hospitalId));
    }

    @GetMapping(value = "/patients", consumes = MediaType.ALL_VALUE)
    @PreAuthorize(CLINICIANS)
    @Operation(summary = "Per patient of the hospital: the urgent alerts nobody has acknowledged, and the oldest")
    public ResponseEntity<List<UrgentAlertPatientMarkDTO>> patients(Authentication auth) {
        UUID userId = requireUserId(auth);
        UUID hospitalId = requireHospital(auth);
        requireClinicianAt(userId, hospitalId);
        return ResponseEntity.ok(overview.patientMarks(hospitalId));
    }

    @GetMapping(value = "/incidents", consumes = MediaType.ALL_VALUE)
    @PreAuthorize(REGISTER_READERS)
    @Operation(summary = "The register of unresolved urgent alerts of the hospital")
    public ResponseEntity<List<UrgentAlertIncidentDTO>> incidents(Authentication auth) {
        UUID userId = requireUserId(auth);
        UUID hospitalId = requireHospital(auth);
        // Review of #1393 (F2): the roles held AT the acting hospital, never the union of the token's assignments.
        boolean administrator = roleValidator.isHospitalAdmin(userId, hospitalId);
        boolean doctor = isDoctorAt(userId, hospitalId);
        return ResponseEntity.ok(overview.incidents(userId, hospitalId, administrator, doctor));
    }

    private boolean isDoctorAt(UUID userId, UUID hospitalId) {
        return roleValidator.isDoctor(userId, hospitalId) || roleValidator.isPhysician(userId, hospitalId)
            || roleValidator.isSurgeon(userId, hospitalId);
    }

    /**
     * Review of #1393 (F2): a clinician's role at the acting hospital. A role held at another hospital of the same
     * account reads nothing here.
     */
    private void requireClinicianAt(UUID userId, UUID hospitalId) {
        boolean clinician = isDoctorAt(userId, hospitalId) || roleValidator.isNurse(userId, hospitalId)
            || roleValidator.isMidwife(userId, hospitalId) || roleValidator.isLabDirector(userId, hospitalId);
        if (!clinician) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(CLINICIAN_REFUSAL_KEY));
        }
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
