package com.example.hms.controller;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PharmacyType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.PharmacyLocationResponseDTO;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.service.PharmacyDirectoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/pharmacies")
@RequiredArgsConstructor
@Tag(name = "Pharmacy Directory", description = "Helper APIs for prescription workflows.")
public class PharmacyDirectoryController {

    private final PharmacyDirectoryService pharmacyDirectoryService;
    private final PharmacyRepository pharmacyRepository;
    private final ControllerAuthUtils authUtils;

    @Operation(
        summary = "List community / partner pharmacies for the current hospital",
        description = "Used by the SMS-dispatch picker on the prescription page. "
            + "Returns active pharmacies whose type is COMMUNITY_PHARMACY or PARTNER_PHARMACY.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/community")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST')")
    public ResponseEntity<List<PharmacyOptionDTO>> listCommunityPharmacies(
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospital = resolveHospital(auth, hospitalId);
        List<Pharmacy> community = pharmacyRepository
            .findByHospitalIdAndPharmacyTypeAndActiveTrue(resolvedHospital, PharmacyType.COMMUNITY_PHARMACY);
        List<Pharmacy> partner = pharmacyRepository
            .findByHospitalIdAndPharmacyTypeAndActiveTrue(resolvedHospital, PharmacyType.PARTNER_PHARMACY);
        List<PharmacyOptionDTO> options = java.util.stream.Stream.concat(community.stream(), partner.stream())
            .map(p -> new PharmacyOptionDTO(
                p.getId(),
                p.getName(),
                p.getPhoneNumber(),
                p.getPharmacyType() != null ? p.getPharmacyType().name() : null))
            .toList();
        return ResponseEntity.ok(options);
    }

    /** Lightweight projection for the SMS-dispatch dropdown. */
    public record PharmacyOptionDTO(UUID id, String name, String phoneNumber, String pharmacyType) { }

    @GetMapping("/patients/{patientId}")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST')")
    public ResponseEntity<List<PharmacyLocationResponseDTO>> listPatientPharmacies(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        Authentication auth
    ) {
        authUtils.requireAuth(auth);
        UUID resolvedHospital = resolveHospital(auth, hospitalId);
        List<PharmacyLocationResponseDTO> pharmacies = pharmacyDirectoryService
            .listPatientPharmacies(patientId, resolvedHospital);
        return ResponseEntity.ok(pharmacies);
    }

    /**
     * The hospital both handlers act at, resolved the way the rest of the
     * codebase resolves it, never from a raw caller-supplied value.
     *
     * <p>{@link ControllerAuthUtils#resolveHospitalScope} validates a
     * {@code hospitalId} parameter against the caller's active assignments
     * (any hospital for a super-admin), then falls back to the request
     * context (the {@code X-Hospital-Id} header after the security filters
     * validated it) and the assignment table. It answers {@code null} for a
     * super-admin who named no hospital parameter even when their scope chip
     * pinned one, so the validated context is consulted once more for that
     * case. A super-admin does reach these handlers: {@code RoleExpansion}
     * grants {@code ROLE_SUPER_ADMIN} holders {@code ROLE_DOCTOR} and
     * {@code ROLE_NURSE}, which the {@code @PreAuthorize} admits.
     *
     * <p>{@code /patients/{patientId}} used to return the raw parameter, then
     * the raw {@code X-Hospital-Id} header, unchecked, so any clinician could
     * name another hospital and be served the pharmacy options of a patient
     * registered there. {@code /community} validated both, but read the header
     * itself rather than the context the filters validated.
     */
    private UUID resolveHospital(Authentication auth, UUID requestedHospitalId) {
        UUID resolved = authUtils.resolveHospitalScope(auth, requestedHospitalId, true);
        if (resolved == null) {
            resolved = authUtils.contextHospitalId();
        }
        if (resolved == null) {
            throw new BusinessException(
                "Hospital context is required. Provide hospitalId parameter or select an active hospital.");
        }
        return resolved;
    }
}
