package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.payload.dto.recordaccess.MinorReleaseOverviewDTO;
import com.example.hms.payload.dto.recordaccess.MinorResultReleaseRequestDTO;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.recordaccess.MinorResultReleaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Policy 2026-11-20 (provisional): a doctor or a midwife who has care of a
 * patient under 18 releases one sensitive result to one verified parent or
 * guardian (Loi 030-2008, art. 17 and 18). The result is hidden from every
 * family proxy until then, and is never blocked for good.
 *
 * <p>A clinical act: the caller holds the DOCTOR or MIDWIFE role on their
 * own (never a super-admin alone, never a support session), and the service
 * checks the treatment relationship at the acting hospital.
 */
@Tag(name = "Record access", description = "Release of a minor's sensitive result to a parent or guardian")
@RestController
@RequiredArgsConstructor
@RequestMapping("/patients/{patientId}/family-releases")
public class MinorResultReleaseController {

    static final String RELEASE_ROLES =
        "T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'DOCTOR','MIDWIFE')";

    private final MinorResultReleaseService releaseService;
    private final ControllerAuthUtils authUtils;

    @Operation(summary = "The parents' grants and the results hidden from them",
        description = "For a patient under 18: the active parent and guardian family-access grants, and the "
            + "released lab results of the acting hospital that a proxy does not read, with their releases.")
    @GetMapping
    @PreAuthorize(RELEASE_ROLES)
    public ResponseEntity<MinorReleaseOverviewDTO> overview(@PathVariable UUID patientId, Authentication auth) {
        return ResponseEntity.ok(releaseService.overview(patientId, authUtils.resolveUserId(auth).orElse(null)));
    }

    @Operation(summary = "Record that the holder of a grant is the patient's parent or legal guardian",
        description = "Staff checked the relationship. Only a verified parent or guardian can receive a release.")
    @WriteAudited(skip = true, reason = "service emits PROXY_RELATIONSHIP_VERIFIED after commit")
    @PostMapping("/guardians/{grantId}/verified")
    @PreAuthorize(RELEASE_ROLES)
    public ResponseEntity<MinorReleaseOverviewDTO> markGuardianVerified(@PathVariable UUID patientId,
                                                                        @PathVariable UUID grantId,
                                                                        Authentication auth) {
        return ResponseEntity.ok(releaseService.verifyGuardian(patientId, grantId,
            authUtils.resolveUserId(auth).orElse(null)));
    }

    @Operation(summary = "Release one sensitive result to one parent or guardian",
        description = "The patient is under 18, the grant is a verified parent's or guardian's, and a reason is "
            + "given. That parent then reads this result; every other sensitive result stays hidden.")
    @WriteAudited(skip = true, reason = "service emits SENSITIVE_RESULT_RELEASED_TO_PROXY after commit, ids only")
    @PostMapping("/lab-orders/{labOrderId}")
    @PreAuthorize(RELEASE_ROLES)
    public ResponseEntity<MinorReleaseOverviewDTO> release(@PathVariable UUID patientId,
                                                           @PathVariable UUID labOrderId,
                                                           @Valid @RequestBody MinorResultReleaseRequestDTO body,
                                                           Authentication auth) {
        return ResponseEntity.ok(releaseService.release(patientId, labOrderId, body.grantId(), body.reason(),
            Boolean.TRUE.equals(body.relationshipVerified()), authUtils.resolveUserId(auth).orElse(null)));
    }
}
