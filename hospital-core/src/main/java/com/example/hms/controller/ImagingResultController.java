package com.example.hms.controller;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportStatusUpdateRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;
import com.example.hms.service.ImagingCriticalNotificationService;
import com.example.hms.service.ImagingReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The radiology reading room.
 *
 * <p>Until Tier 2 item 26 this controller was read-only in practice:
 * {@code ImagingReportService.createReport} and {@code updateReport} existed
 * and were unit-tested but had no endpoint, so no imaging report could ever be
 * created and the portal's Results view listed rows that could not exist.
 *
 * <p><b>Class-level {@code @PreAuthorize} is load-bearing.</b> There is no
 * {@code /imaging/**} matcher in {@code SecurityConfig}, so these paths ride
 * {@code anyRequest().authenticated()} — a method with a forgotten annotation
 * would otherwise be reachable by every authenticated principal, patients
 * included. The class-level rule is the union of the method rules, so an
 * un-annotated method fails closed rather than open (the {@code /recalls}
 * precedent from PR #476).
 */
@RestController
@RequestMapping("/imaging/results")
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('VIEW_IMAGING_RESULTS','CREATE_RADIOLOGY_REPORTS','SIGN_IMAGING_REPORTS',"
    + "'ACKNOWLEDGE_CRITICAL_RESULTS') "
    + "or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','RADIOLOGIST','PATIENT')")
@Tag(name = "Imaging Results", description = "Author, sign, and read radiology reports; acknowledge critical findings")
public class ImagingResultController {

    private final ImagingReportService imagingReportService;
    private final ImagingCriticalNotificationService criticalNotificationService;

    // ── Authoring ────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAuthority('CREATE_RADIOLOGY_REPORTS') or hasAnyRole('SUPER_ADMIN','RADIOLOGIST','DOCTOR')")
    @Operation(summary = "Author an imaging report",
               description = "File a new report against an imaging order. Version numbering is derived "
                   + "server-side. A study that already carries a signed report only accepts an "
                   + "ADDENDUM, CORRECTED or AMENDED follow-up.")
    public ResponseEntity<ImagingReportResponseDTO> createReport(
        @Valid @RequestBody ImagingReportUpsertRequestDTO request
    ) {
        ImagingReportResponseDTO report = imagingReportService.createReport(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(report);
    }

    @PutMapping("/{reportId}")
    @PreAuthorize("hasAuthority('CREATE_RADIOLOGY_REPORTS') or hasAnyRole('SUPER_ADMIN','RADIOLOGIST','DOCTOR')")
    @Operation(summary = "Revise an unsigned imaging report",
               description = "Signed reports are closed to edits; corrections are filed as a new version.")
    public ResponseEntity<ImagingReportResponseDTO> updateReport(
        @PathVariable UUID reportId,
        @Valid @RequestBody ImagingReportUpsertRequestDTO request
    ) {
        ImagingReportResponseDTO report = imagingReportService.updateReport(reportId, request);
        return ResponseEntity.ok(report);
    }

    @PostMapping("/{reportId}/sign")
    @PreAuthorize("hasAuthority('SIGN_IMAGING_REPORTS') or hasAnyRole('SUPER_ADMIN','RADIOLOGIST','DOCTOR')")
    @Operation(summary = "Sign an imaging report",
               description = "The only path to FINAL. Signer identity and time come from the "
                   + "authenticated caller, never the request body, and a SHA-256 digest over the "
                   + "report content is stored as tamper-evidence. Re-signing is refused.")
    public ResponseEntity<ImagingReportResponseDTO> signReport(@PathVariable UUID reportId) {
        return ResponseEntity.ok(imagingReportService.signReport(reportId));
    }

    // ── Reads ────────────────────────────────────────────────────────────

    @GetMapping("/{reportId}")
    @PreAuthorize("hasAuthority('VIEW_IMAGING_RESULTS') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','RADIOLOGIST','PATIENT')")
    @Operation(summary = "Get imaging report by ID",
               description = "Retrieve full imaging report with findings, impression, and PACS viewer URL")
    public ResponseEntity<ImagingReportResponseDTO> getReport(@PathVariable UUID reportId) {
        ImagingReportResponseDTO report = imagingReportService.getReport(reportId);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/order/{orderId}")
    @PreAuthorize("hasAuthority('VIEW_IMAGING_RESULTS') or hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE','RADIOLOGIST','PATIENT')")
    @Operation(summary = "Get latest report for imaging order",
               description = "Retrieve the most recent report version for a specific imaging order")
    public ResponseEntity<ImagingReportResponseDTO> getLatestReportForOrder(@PathVariable UUID orderId) {
        ImagingReportResponseDTO report = imagingReportService.getLatestReportForOrder(orderId);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/order/{orderId}/all")
    @PreAuthorize("hasAuthority('VIEW_IMAGING_RESULTS') or hasAnyRole('SUPER_ADMIN','DOCTOR','RADIOLOGIST')")
    @Operation(summary = "Get all report versions for imaging order",
               description = "Retrieve complete history of report versions (preliminary, final, addenda)")
    public ResponseEntity<List<ImagingReportResponseDTO>> getAllReportsForOrder(@PathVariable UUID orderId) {
        List<ImagingReportResponseDTO> reports = imagingReportService.getReportsForOrder(orderId);
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize("hasAuthority('VIEW_IMAGING_RESULTS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','RADIOLOGIST')")
    @Operation(summary = "List imaging reports for hospital",
               description = "Filter by status (e.g., PRELIMINARY, FINAL) or modality (CT, MRI, X-RAY). "
                   + "The path hospital must be the caller's active hospital; super-admins may name any.")
    public ResponseEntity<List<ImagingReportResponseDTO>> getReportsByHospital(
        @PathVariable UUID hospitalId,
        @RequestParam(required = false) ImagingReportStatus status,
        @RequestParam(required = false) ImagingModality modality
    ) {
        List<ImagingReportResponseDTO> reports;

        if (status != null) {
            reports = imagingReportService.getReportsByHospitalAndStatus(hospitalId, status);
        } else if (modality != null) {
            reports = imagingReportService.getReportsByHospitalAndModality(hospitalId, modality);
        } else {
            // If no filter specified, return by status=FINAL as default
            reports = imagingReportService.getReportsByHospitalAndStatus(hospitalId, ImagingReportStatus.FINAL);
        }

        return ResponseEntity.ok(reports);
    }

    // ── Administrative + critical findings ───────────────────────────────

    @PutMapping("/{reportId}/status")
    @PreAuthorize("hasAuthority('CREATE_RADIOLOGY_REPORTS') or hasAnyRole('SUPER_ADMIN','RADIOLOGIST','DOCTOR')")
    @Operation(summary = "Cancel or void an unsigned report",
               description = "Administrative outcomes only (CANCELLED, ERROR) and a reason is required. "
                   + "Content states come from authoring the report and FINAL only from signing it.")
    public ResponseEntity<ImagingReportResponseDTO> updateReportStatus(
        @PathVariable UUID reportId,
        @Valid @RequestBody ImagingReportStatusUpdateRequestDTO request
    ) {
        ImagingReportResponseDTO report = imagingReportService.updateReportStatus(reportId, request);
        return ResponseEntity.ok(report);
    }

    /**
     * Acknowledge a critical finding.
     *
     * <p>This endpoint could not succeed before Tier 2 item 26. It built a
     * {@code ImagingReportStatusUpdateRequestDTO} carrying only a staff id and
     * a reason, and {@code updateReportStatus} throws when the payload has no
     * status — so every call returned 400. Even had it passed, it wrote a
     * status-history row and never touched
     * {@code criticalResultAcknowledgedBy} or {@code ...At}, so nothing was
     * acknowledged. And the acknowledging clinician arrived as a query
     * parameter, meaning a caller could name someone else as having taken the
     * call. All three are fixed: the service stamps the authenticated caller.
     */
    @PutMapping("/{reportId}/acknowledge-critical")
    @PreAuthorize("hasAuthority('ACKNOWLEDGE_CRITICAL_RESULTS') or hasAnyRole('SUPER_ADMIN','DOCTOR','RADIOLOGIST')")
    @Operation(summary = "Acknowledge critical imaging result",
               description = "Records the authenticated clinician as having taken responsibility for a "
                   + "critical finding. Refused when the report carries no critical flag.")
    public ResponseEntity<ImagingReportResponseDTO> acknowledgeCriticalResult(@PathVariable UUID reportId) {
        return ResponseEntity.ok(imagingReportService.acknowledgeCriticalResult(reportId));
    }

    /**
     * Run the critical-finding escalation sweep now.
     *
     * <p>Twin of {@code POST /lab-results/critical-escalation/run}: the
     * scheduled sweep runs every five minutes, and a manual trigger is what
     * makes the loop testable in a deployed environment without waiting for it.
     */
    @PostMapping("/critical-escalation/run")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('HOSPITAL_ADMIN')")
    @Operation(summary = "Run the critical imaging escalation sweep",
               description = "Escalates critical findings still unacknowledged past the configured delay. "
                   + "Returns the number of reports escalated on this pass.")
    public ResponseEntity<Map<String, Integer>> runCriticalEscalation() {
        return ResponseEntity.ok(Map.of("escalated", criticalNotificationService.escalateOverdue()));
    }
}
