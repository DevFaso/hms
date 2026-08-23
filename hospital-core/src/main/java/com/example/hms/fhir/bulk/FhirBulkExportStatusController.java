package com.example.hms.fhir.bulk;

import com.example.hms.model.platform.FhirBulkExportFile;
import com.example.hms.model.platform.FhirBulkExportJob;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Poll / cancel / download surface for FHIR Bulk Data Access (P3 #24).
 * Mounted at {@code /api/fhir-bulk-status/{jobId}} (sibling to HAPI's
 * {@code /api/fhir/*} servlet, which captures that whole space).
 *
 * <p>Spec semantics, now complete:
 * <ul>
 *   <li>QUEUED / IN_PROGRESS → {@code 202 Accepted} + {@code X-Progress}
 *       + {@code Retry-After}.</li>
 *   <li>COMPLETED → {@code 200 OK} + the bulk-data completion manifest
 *       ({@code transactionTime}, {@code request},
 *       {@code requiresAccessToken: true}, {@code output[]}).</li>
 *   <li>FAILED → {@code 500} + a FHIR {@code OperationOutcome} carrying
 *       the failure message.</li>
 *   <li>CANCELLED / unknown / cross-tenant → {@code 404 Not Found}
 *       (post-DELETE polls are 404 per the spec; cross-tenant rejection
 *       stays invisible).</li>
 *   <li>{@code GET .../file/{fileName}} streams one NDJSON output file —
 *       the authenticated download that makes the manifest's
 *       {@code requiresAccessToken: true} literally true. Output never
 *       lives under permitAll {@code /uploads/**}.</li>
 * </ul>
 *
 * <p>Class-level role gate: a bulk export is a mass PHI extract, and
 * {@code /fhir-bulk-status/**} would otherwise ride
 * {@code anyRequest().authenticated()} — readable by any authenticated
 * user including ROLE_PATIENT. Mirrors the kickoff gate in
 * {@link FhirBulkExportService#requireBulkExportRole()}.
 */
@RestController
@RequestMapping("/fhir-bulk-status")
@PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN')")
public class FhirBulkExportStatusController {

    private static final String FHIR_JSON = "application/fhir+json";
    private static final String NDJSON = "application/fhir+ndjson";

    private final FhirBulkExportService service;

    public FhirBulkExportStatusController(FhirBulkExportService service) {
        this.service = service;
    }

    @GetMapping(value = "/{jobId}")
    public ResponseEntity<String> getStatus(@PathVariable UUID jobId) {
        if (!service.isEnabled()) {
            return notImplemented();
        }
        Optional<FhirBulkExportJob> resolved = service.getJob(jobId);
        if (resolved.isEmpty()
            || resolved.get().getStatus() == FhirBulkExportJob.Status.CANCELLED) {
            return notFound(jobId);
        }
        FhirBulkExportJob job = resolved.get();
        return switch (job.getStatus()) {
            case QUEUED, IN_PROGRESS -> inProgress(job);
            case COMPLETED -> manifest(job);
            case FAILED -> failed(job);
            case CANCELLED -> notFound(jobId); // unreachable — filtered above
        };
    }

    @DeleteMapping(value = "/{jobId}", produces = FHIR_JSON)
    public ResponseEntity<String> cancel(@PathVariable UUID jobId) {
        if (!service.isEnabled()) {
            return notImplemented();
        }
        boolean cancelled = service.cancelExport(jobId);
        if (!cancelled) {
            return notFound(jobId);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * Stream one NDJSON output file of a completed job. The name is
     * matched against the job's recorded output rows — raw client input
     * never touches the filesystem.
     */
    @GetMapping("/{jobId}/file/{fileName}")
    public ResponseEntity<Resource> download(@PathVariable UUID jobId,
                                             @PathVariable String fileName) {
        if (!service.isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }
        Optional<FhirBulkExportJob> resolved = service.getJob(jobId);
        if (resolved.isEmpty()
            || resolved.get().getStatus() != FhirBulkExportJob.Status.COMPLETED) {
            return ResponseEntity.notFound().build();
        }
        Path path = service.resolveOutputFile(resolved.get(), fileName);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(NDJSON))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
            .body(new FileSystemResource(path));
    }

    /* ── response builders ─────────────────────────────────────────── */

    private static ResponseEntity<String> inProgress(FhirBulkExportJob job) {
        String progress = job.getStatus() == FhirBulkExportJob.Status.QUEUED
            ? "queued"
            : "in-progress — " + job.getProcessedPatients()
                + (job.getTotalPatients() != null ? "/" + job.getTotalPatients() : "")
                + " patient(s) processed";
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Progress", progress);
        headers.add("Retry-After", "120");
        return ResponseEntity.status(HttpStatus.ACCEPTED).headers(headers).build();
    }

    private ResponseEntity<String> manifest(FhirBulkExportJob job) {
        List<FhirBulkExportFile> files = service.filesFor(job.getId());
        // The spec wants absolute output URLs; derive them from the URL
        // the client just polled, which honours X-Forwarded-* the same
        // way the client reached us.
        String base = ServletUriComponentsBuilder.fromCurrentRequestUri()
            .replaceQuery(null).toUriString();
        StringBuilder output = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            FhirBulkExportFile file = files.get(i);
            if (i > 0) output.append(',');
            output.append("{\"type\":\"").append(file.getResourceType())
                .append("\",\"url\":\"").append(base).append("/file/").append(file.getFileName())
                .append("\",\"count\":").append(file.getResourceCount()).append('}');
        }
        output.append(']');
        Instant transactionTime = job.getStartedAt() != null
            ? job.getStartedAt() : job.getRequestedAt();
        String body = "{"
            + "\"transactionTime\":\"" + transactionTime + "\","
            + "\"request\":\"" + (job.getRequestUrl() == null ? "" : job.getRequestUrl()) + "\","
            + "\"requiresAccessToken\":true,"
            + "\"output\":" + output + ","
            + "\"error\":[]"
            + "}";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }

    private static ResponseEntity<String> failed(FhirBulkExportJob job) {
        String message = job.getErrorMessage() == null
            ? "The bulk-export job failed." : job.getErrorMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.valueOf(FHIR_JSON))
            .body("{\"resourceType\":\"OperationOutcome\",\"issue\":[{"
                + "\"severity\":\"error\",\"code\":\"exception\","
                + "\"diagnostics\":\"" + message.replace("\"", "'") + "\""
                + "}]}");
    }

    private static ResponseEntity<String> notImplemented() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .contentType(MediaType.valueOf(FHIR_JSON))
            .body("{\"resourceType\":\"OperationOutcome\",\"issue\":[{"
                + "\"severity\":\"error\",\"code\":\"not-supported\","
                + "\"diagnostics\":\"FHIR $export is disabled — set "
                + "app.fhir.operations.bulk-export.enabled=true to opt in.\""
                + "}]}");
    }

    private static ResponseEntity<String> notFound(UUID jobId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.valueOf(FHIR_JSON))
            .body("{\"resourceType\":\"OperationOutcome\",\"issue\":[{"
                + "\"severity\":\"error\",\"code\":\"not-found\","
                + "\"diagnostics\":\"No bulk-export job " + jobId
                + " found at the active hospital scope.\""
                + "}]}");
    }
}
