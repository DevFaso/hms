package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.PharmacyLocationResponseDTO;
import com.example.hms.service.PharmacyDirectoryService;
import com.example.hms.utility.RoleValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/pharmacies")
@RequiredArgsConstructor
@Tag(name = "Pharmacy Directory", description = "Helper APIs for prescription workflows.")
public class PharmacyDirectoryController {

    private final PharmacyDirectoryService pharmacyDirectoryService;
    private final RoleValidator roleValidator;

    @Operation(
        summary = "List pharmacies available for a patient",
        description = "Returns preferred, hospital, and mail-order options to support prescribing",
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponse(
        responseCode = "200",
        description = "Pharmacies returned successfully",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = PharmacyLocationResponseDTO.class)))
    )
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
            throw new BusinessException("Authentication is required for pharmacy directory access.");
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
            String direct = jwt.getClaimAsString("hospitalId");
            if (direct != null && !direct.isBlank()) {
                try {
                    return UUID.fromString(direct);
                } catch (IllegalArgumentException ignored) {
                    // fall through
                }
            }
            Object raw = jwt.getClaims().get("hospitalId");
            if (raw instanceof UUID uuid) {
                return uuid;
            }
            if (raw instanceof String s && !s.isBlank()) {
                try {
                    return UUID.fromString(s);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
