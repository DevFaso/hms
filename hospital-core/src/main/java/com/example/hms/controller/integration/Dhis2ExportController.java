package com.example.hms.controller.integration;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.integration.Dhis2ExportRunMapper;
import com.example.hms.model.integration.Dhis2ExportRun;
import com.example.hms.payload.dto.integration.Dhis2ExportRunResponseDTO;
import com.example.hms.payload.dto.integration.Dhis2TriggerRequestDTO;
import com.example.hms.repository.integration.Dhis2ExportRunRepository;
import com.example.hms.service.integration.DhisAdxExportService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/integrations/dhis2/exports")
@RequiredArgsConstructor
@Tag(name = "DHIS2 Exports",
    description = "Manual trigger and run history for the DHIS2 ADX export pipeline")
@PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
public class Dhis2ExportController {

    private final DhisAdxExportService exportService;
    private final Dhis2ExportRunRepository runRepository;
    private final Dhis2ExportRunMapper runMapper;
    private final ControllerAuthUtils authUtils;

    @PostMapping("/trigger")
    @Operation(summary = "Manually trigger a DHIS2 ADX export for a hospital + dataset + period")
    public ResponseEntity<Dhis2ExportRunResponseDTO> trigger(
        Authentication auth,
        @Valid @RequestBody Dhis2TriggerRequestDTO body
    ) {
        authUtils.requireAuth(auth);
        final UUID scoped = authUtils.resolveHospitalScope(auth, body.hospitalId(), false);
        if (scoped == null || !scoped.equals(body.hospitalId())) {
            throw new BusinessException("Access denied for hospital: " + body.hospitalId());
        }
        final UUID staffId = authUtils.resolveUserId(auth).orElse(null);
        final Dhis2ExportRun run = exportService.triggerImmunizationsExport(
            body.hospitalId(),
            body.datasetUid(),
            body.periodType(),
            body.periodIso(),
            staffId);
        return ResponseEntity.ok(runMapper.toResponseDTO(run));
    }

    @GetMapping("/runs")
    @Operation(summary = "List DHIS2 export runs (latest first, paginated)")
    public ResponseEntity<Page<Dhis2ExportRunResponseDTO>> listRuns(
        Authentication auth,
        @RequestParam("hospitalId") UUID hospitalId,
        Pageable pageable
    ) {
        authUtils.requireAuth(auth);
        final UUID scoped = authUtils.resolveHospitalScope(auth, hospitalId, false);
        if (scoped == null || !scoped.equals(hospitalId)) {
            throw new BusinessException("Access denied for hospital: " + hospitalId);
        }
        return ResponseEntity.ok(runRepository
            .findByHospital_IdOrderByStartedAtDesc(hospitalId, pageable)
            .map(runMapper::toResponseDTO));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Get a single DHIS2 export run by id")
    public ResponseEntity<Dhis2ExportRunResponseDTO> getRun(
        Authentication auth,
        @PathVariable("id") UUID id
    ) {
        authUtils.requireAuth(auth);
        final Dhis2ExportRun run = runRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("DHIS2 run not found: " + id));
        final UUID scoped = authUtils.resolveHospitalScope(auth, run.getHospital().getId(), false);
        if (scoped == null || !scoped.equals(run.getHospital().getId())) {
            throw new BusinessException(
                "Access denied for hospital: " + run.getHospital().getId());
        }
        return ResponseEntity.ok(runMapper.toResponseDTO(run));
    }
}
