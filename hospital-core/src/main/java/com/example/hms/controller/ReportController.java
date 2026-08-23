package com.example.hms.controller;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.ReportDefinition;
import com.example.hms.model.platform.ReportRun;
import com.example.hms.payload.dto.reporting.ReportDefinitionRequestDTO;
import com.example.hms.payload.dto.reporting.ReportDefinitionResponseDTO;
import com.example.hms.payload.dto.reporting.ReportRunResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ReportDefinitionRepository;
import com.example.hms.repository.ReportRunRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.reporting.ScheduledReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled-report definitions + run history (P3 #25a).
 *
 * <p>Class-level gate on purpose: /reports has no SecurityConfig
 * matcher, so it rides {@code anyRequest().authenticated()} and a
 * forgotten method annotation would fail OPEN to any authenticated
 * user, including ROLE_PATIENT. Configuring who receives emailed
 * aggregates is administrative.
 */
@RestController
@RequestMapping(value = "/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')")
@Tag(name = "Scheduled Reports", description = "Aggregate CSV reports emailed on a schedule")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    private final ReportDefinitionRepository definitionRepository;
    private final ReportRunRepository runRepository;
    private final ScheduledReportService scheduledReportService;
    private final HospitalRepository hospitalRepository;

    @PostMapping
    @Operation(summary = "Create a scheduled report",
        description = "Aggregate-only CSV emailed to the recipients each closed period.")
    public ResponseEntity<ReportDefinitionResponseDTO> create(
            @Valid @RequestBody ReportDefinitionRequestDTO request,
            @AuthenticationPrincipal UserDetails principal) {
        UUID hospitalId = requireHospital();
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));
        validateRecipients(request.getRecipients());
        ReportDefinition definition = definitionRepository.save(ReportDefinition.builder()
                .hospital(hospital)
                .name(request.getName().trim())
                .reportType(request.getReportType())
                .period(request.getPeriod())
                .recipients(request.getRecipients().trim())
                .createdBy(principal != null ? principal.getUsername() : "system")
                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(definition));
    }

    @GetMapping
    @Operation(summary = "List this hospital's report definitions")
    public ResponseEntity<List<ReportDefinitionResponseDTO>> list() {
        UUID hospitalId = requireHospital();
        return ResponseEntity.ok(definitionRepository
                .findByHospital_IdOrderByCreatedAtDesc(hospitalId).stream()
                .map(ReportController::toDto)
                .toList());
    }

    @GetMapping("/{id}/runs")
    @Operation(summary = "Run history for one definition, newest first")
    public ResponseEntity<List<ReportRunResponseDTO>> runs(@PathVariable UUID id) {
        ReportDefinition definition = loadScoped(id);
        return ResponseEntity.ok(runRepository
                .findTop50ByDefinition_IdOrderByCreatedAtDesc(definition.getId()).stream()
                .map(ReportController::toDto)
                .toList());
    }

    @PostMapping("/{id}/run")
    @Operation(summary = "Generate and email one period now",
        description = "Defaults to the prior closed period. A FAILED period may be retried; "
            + "a period that already succeeded is refused — duplicate email needs a new period.")
    public ResponseEntity<ReportRunResponseDTO> runNow(
            @PathVariable UUID id,
            @RequestParam(required = false) String periodToken) {
        ReportDefinition definition = loadScoped(id);
        String token = periodToken != null && !periodToken.isBlank()
                ? periodToken.trim()
                : com.example.hms.service.reporting.ReportGenerationService
                    .priorPeriodToken(definition.getPeriod(), LocalDate.now());
        ReportRun run = scheduledReportService.execute(definition, token, true);
        return ResponseEntity.ok(toDto(run));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Stop a scheduled report (never deletes)")
    public ResponseEntity<ReportDefinitionResponseDTO> deactivate(@PathVariable UUID id) {
        ReportDefinition definition = loadScoped(id);
        definition.setActive(false);
        return ResponseEntity.ok(toDto(definitionRepository.save(definition)));
    }

    @PostMapping("/{id}/reactivate")
    @Operation(summary = "Resume a stopped scheduled report")
    public ResponseEntity<ReportDefinitionResponseDTO> reactivate(@PathVariable UUID id) {
        ReportDefinition definition = loadScoped(id);
        definition.setActive(true);
        return ResponseEntity.ok(toDto(definitionRepository.save(definition)));
    }

    /* ── helpers ───────────────────────────────────────────────────── */

    private ReportDefinition loadScoped(UUID id) {
        return definitionRepository.findByIdAndHospital_Id(id, requireHospital())
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Report definition not found with ID: " + id));
    }

    private static UUID requireHospital() {
        UUID hospitalId = HospitalContextHolder.getContext()
                .map(HospitalContext::getActiveHospitalId)
                .orElse(null);
        if (hospitalId == null) {
            throw new BusinessException(
                "An active hospital is required to manage scheduled reports.");
        }
        return hospitalId;
    }

    /** Light shape check — the mail server is the real authority. */
    private static void validateRecipients(String recipients) {
        for (String email : recipients.split(",")) {
            String trimmed = email.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.contains("@") || trimmed.length() > 254) {
                throw new BusinessException("'" + trimmed + "' is not a valid email address.");
            }
        }
    }

    private static ReportDefinitionResponseDTO toDto(ReportDefinition definition) {
        return ReportDefinitionResponseDTO.builder()
                .id(definition.getId())
                .hospitalId(definition.getHospital().getId())
                .name(definition.getName())
                .reportType(definition.getReportType())
                .period(definition.getPeriod())
                .recipients(definition.getRecipients())
                .active(definition.isActive())
                .createdBy(definition.getCreatedBy())
                .createdAt(definition.getCreatedAt())
                .build();
    }

    private static ReportRunResponseDTO toDto(ReportRun run) {
        return ReportRunResponseDTO.builder()
                .id(run.getId())
                .periodToken(run.getPeriodToken())
                .status(run.getStatus())
                .rowCount(run.getRowCount())
                .errorMessage(run.getErrorMessage())
                .generatedAt(run.getGeneratedAt())
                .createdAt(run.getCreatedAt())
                .build();
    }
}
