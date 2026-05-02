package com.example.hms.controller;

import com.example.hms.enums.PharmacyType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.PharmacyLocationResponseDTO;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.service.PharmacyDirectoryService;
import com.example.hms.utility.RoleValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/pharmacies")
@RequiredArgsConstructor
@Tag(name = "Pharmacy Directory", description = "Helper APIs for prescription workflows.")
public class PharmacyDirectoryController {

    private final PharmacyDirectoryService pharmacyDirectoryService;
    private final RoleValidator roleValidator;
    private final PharmacyRepository pharmacyRepository;

    @Operation(
        summary = "List community / partner pharmacies for the current hospital",
        description = "Used by the SMS-dispatch picker on the prescription page. "
            + "Returns active pharmacies whose type is COMMUNITY_PHARMACY or PARTNER_PHARMACY.",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/community")
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN')")
    public ResponseEntity<List<PharmacyOptionDTO>> listCommunityPharmacies(
        @RequestParam(required = false) UUID hospitalId,
        @RequestHeader(value = "X-Hospital-Id", required = false) UUID headerHospitalId,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID resolvedHospital = resolveHospitalContext(auth, hospitalId, headerHospitalId);
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
    @PreAuthorize("hasAnyAuthority('ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE','ROLE_PHARMACIST','ROLE_HOSPITAL_ADMIN')")
    public ResponseEntity<List<PharmacyLocationResponseDTO>> listPatientPharmacies(
        @PathVariable UUID patientId,
        @RequestParam(required = false) UUID hospitalId,
        @RequestHeader(value = "X-Hospital-Id", required = false) UUID headerHospitalId,
        Authentication auth
    ) {
        requireAuth(auth);
        UUID resolvedHospital = resolveHospitalContext(auth, hospitalId, headerHospitalId);
        List<PharmacyLocationResponseDTO> pharmacies = pharmacyDirectoryService
            .listPatientPharmacies(patientId, resolvedHospital);
        return ResponseEntity.ok(pharmacies);
    }

    private void requireAuth(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Authentication is required for pharmacy directory access.");
        }
    }

    private UUID resolveHospitalContext(Authentication auth, UUID requestedHospitalId, UUID headerHospitalId) {
        if (requestedHospitalId != null) {
            return requestedHospitalId;
        }
        if (headerHospitalId != null) {
            return headerHospitalId;
        }
        UUID fromToken = extractHospitalId(auth);
        if (fromToken != null) {
            return fromToken;
        }
        UUID fromAssignment = roleValidator.getCurrentHospitalId();
        if (fromAssignment != null) {
            return fromAssignment;
        }
        throw new BusinessException("Hospital context is required. Provide hospitalId parameter, X-Hospital-Id header, or include hospitalId claim in the token.");
    }

    private UUID extractHospitalId(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jat) {
            Jwt jwt = jat.getToken();
            for (String claimKey : List.of("primaryHospitalId", "hospitalId")) {
                UUID result = tryParseUuidClaim(jwt, claimKey);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static UUID tryParseUuidClaim(Jwt jwt, String claimKey) {
        String direct = jwt.getClaimAsString(claimKey);
        if (direct != null && !direct.isBlank()) {
            try {
                return UUID.fromString(direct);
            } catch (IllegalArgumentException ignored) { /* try raw */ }
        }
        Object raw = jwt.getClaims().get(claimKey);
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String str && !str.isBlank()) {
            try {
                return UUID.fromString(str);
            } catch (IllegalArgumentException ignored) { /* not a valid UUID */ }
        }
        return null;
    }
}
