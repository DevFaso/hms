package com.example.hms.controller;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.portal.DisclosureAccountingDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.service.disclosure.DisclosureAccountingService;
import com.example.hms.utility.RoleValidator;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff-facing accounting of disclosures (Tier 2 item 39).
 *
 * <p>The patient's own view lives on {@code /me/patient/disclosures} and
 * resolves the patient from the JWT, so it cannot be pointed at anyone else.
 * This is the counterpart for the office that answers "who has seen my
 * record?" when a patient asks in person rather than through the portal.
 *
 * <p>Deliberately narrow on both axes:
 *
 * <ul>
 *   <li><b>Role.</b> Hospital admin, quality manager, super-admin. This
 *       endpoint returns the complete history of who opened a chart, which
 *       is a staff-surveillance surface as much as a patient-rights one.
 *       There is no dedicated health-information-management role in this
 *       deployment; when one exists it belongs here and the clinical roles
 *       still do not.</li>
 *   <li><b>Tenant.</b> The patient must be registered at the caller's
 *       hospital. A patient elsewhere reads as missing rather than
 *       forbidden — 404 not 403 — because distinguishing the two tells an
 *       unauthorised caller that the record exists.</li>
 * </ul>
 */
@RestController
@RequestMapping("/patients/{patientId}/disclosures")
@RequiredArgsConstructor
@Tag(name = "Disclosure Accounting",
     description = "Who has seen, received or exported a patient's record")
public class PatientDisclosureController {

    private final DisclosureAccountingService disclosureAccountingService;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final RoleValidator roleValidator;

    @Operation(summary = "Accounting of disclosures for a patient",
            description = "Classified and counted access history over a date window. "
                + "Includes emergency break-the-glass access and disclosures to insurers.")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_QUALITY_MANAGER','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponseWrapper<DisclosureAccountingDTO>> getDisclosures(
            @PathVariable UUID patientId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 50) Pageable pageable) {

        requirePatientInCallerTenant(patientId);

        DisclosureAccountingDTO accounting =
            disclosureAccountingService.getAccounting(patientId, from, to, pageable);
        return ResponseEntity.ok(ApiResponseWrapper.success(accounting));
    }

    /**
     * Registration, not {@code Patient.hospitalId}: a patient may legitimately
     * be registered at several hospitals and each of those may answer for
     * what happened at its own site. Same reasoning
     * {@code EmpiServiceImpl.requirePatientInTenant} gives.
     *
     * <p>A null active hospital means a real super-admin (see
     * {@link RoleValidator#requireActiveHospitalId()}), which is unscoped by
     * design and the only caller allowed past this without a registration.
     */
    private void requirePatientInCallerTenant(UUID patientId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return;
        }
        if (!registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)) {
            throw new ResourceNotFoundException("Patient not found.");
        }
    }
}
