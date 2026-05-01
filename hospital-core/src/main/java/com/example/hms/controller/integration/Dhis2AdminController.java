package com.example.hms.controller.integration;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.integration.Dhis2DataElementMappingRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2DataElementMappingResponseDTO;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigResponseDTO;
import com.example.hms.service.integration.Dhis2ConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/integrations/dhis2")
@RequiredArgsConstructor
@Tag(name = "DHIS2 Admin",
    description = "Per-hospital DHIS2 ADX export configuration and dataElement mappings")
@PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
public class Dhis2AdminController {

    private final Dhis2ConfigService configService;
    private final ControllerAuthUtils authUtils;

    @GetMapping("/facility")
    @Operation(summary = "Get the DHIS2 facility config for a hospital")
    public ResponseEntity<Dhis2FacilityConfigResponseDTO> getFacility(
        Authentication auth,
        @RequestParam("hospitalId") UUID hospitalId
    ) {
        authUtils.requireAuth(auth);
        final UUID scoped = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (scoped == null || !scoped.equals(hospitalId)) {
            throw new BusinessException("Access denied for hospital: " + hospitalId);
        }
        return configService.getFacilityConfig(hospitalId)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No DHIS2 facility config for hospital " + hospitalId));
    }

    @PutMapping("/facility")
    @Operation(summary = "Create or update the DHIS2 facility config for a hospital")
    public ResponseEntity<Dhis2FacilityConfigResponseDTO> upsertFacility(
        Authentication auth,
        @RequestParam("hospitalId") UUID hospitalId,
        @Valid @RequestBody Dhis2FacilityConfigRequestDTO body
    ) {
        authUtils.requireAuth(auth);
        final UUID scoped = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (scoped == null || !scoped.equals(hospitalId)) {
            throw new BusinessException("Access denied for hospital: " + hospitalId);
        }
        return ResponseEntity.ok(configService.upsertFacilityConfig(hospitalId, body));
    }

    @GetMapping("/mappings")
    @Operation(summary = "List dataElement mappings (paginated, filtered by datasetUid)")
    public ResponseEntity<Page<Dhis2DataElementMappingResponseDTO>> listMappings(
        Authentication auth,
        @RequestParam("hospitalId") UUID hospitalId,
        @RequestParam("datasetUid") String datasetUid,
        Pageable pageable
    ) {
        authUtils.requireAuth(auth);
        final UUID scoped = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (scoped == null || !scoped.equals(hospitalId)) {
            throw new BusinessException("Access denied for hospital: " + hospitalId);
        }
        return ResponseEntity.ok(configService.listMappings(hospitalId, datasetUid, pageable));
    }

    @PostMapping("/mappings")
    @Operation(summary = "Create a new dataElement mapping for a hospital + dataset")
    public ResponseEntity<Dhis2DataElementMappingResponseDTO> createMapping(
        Authentication auth,
        @RequestParam("hospitalId") UUID hospitalId,
        @Valid @RequestBody Dhis2DataElementMappingRequestDTO body
    ) {
        authUtils.requireAuth(auth);
        final UUID scoped = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (scoped == null || !scoped.equals(hospitalId)) {
            throw new BusinessException("Access denied for hospital: " + hospitalId);
        }
        return ResponseEntity.ok(configService.createMapping(hospitalId, body));
    }

    @PutMapping("/mappings/{id}")
    @Operation(summary = "Update an existing dataElement mapping")
    public ResponseEntity<Dhis2DataElementMappingResponseDTO> updateMapping(
        Authentication auth,
        @PathVariable("id") UUID id,
        @RequestParam("hospitalId") UUID hospitalId,
        @Valid @RequestBody Dhis2DataElementMappingRequestDTO body
    ) {
        authUtils.requireAuth(auth);
        final UUID scoped = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (scoped == null || !scoped.equals(hospitalId)) {
            throw new BusinessException("Access denied for hospital: " + hospitalId);
        }
        return ResponseEntity.ok(configService.updateMapping(id, hospitalId, body));
    }

    @DeleteMapping("/mappings/{id}")
    @Operation(summary = "Delete a dataElement mapping")
    public ResponseEntity<Void> deleteMapping(
        Authentication auth,
        @PathVariable("id") UUID id,
        @RequestParam("hospitalId") UUID hospitalId
    ) {
        authUtils.requireAuth(auth);
        final UUID scoped = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (scoped == null || !scoped.equals(hospitalId)) {
            throw new BusinessException("Access denied for hospital: " + hospitalId);
        }
        configService.deleteMapping(id, hospitalId);
        return ResponseEntity.noContent().build();
    }
}
