package com.example.hms.fhir.bulk;

import com.example.hms.model.platform.FhirBulkExportFile;
import com.example.hms.model.platform.FhirBulkExportJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Every body below is SERIALISED, never concatenated.
     *
     * <p>These responses used to be built with string concatenation and a
     * hand-rolled escape, which is wrong in both directions: it mangled
     * legitimate quotes inside a diagnostic message, and it did nothing at all
     * about backslashes, newlines or control characters. The manifest was
     * worse — it interpolated {@code job.getRequestUrl()}, which is derived
     * from the caller's own request, straight into a JSON string literal, so a
     * single quote in a request URL produced a malformed body.
     *
     * <p>Letting Jackson emit the JSON makes escaping correct by construction
     * and removes the value-into-body flow CodeQL reports here. The
     * {@code jobId} path variable it points at is in fact a parsed
     * {@link UUID} — Spring rejects anything malformed before the handler runs,
     * so no payload could survive it — but the concatenation beside it was a
     * real defect regardless.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

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
        List<Map<String, Object>> output = new ArrayList<>();
        for (FhirBulkExportFile file : files) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", file.getResourceType());
            entry.put("url", base + "/file/" + file.getFileName());
            entry.put("count", file.getResourceCount());
            output.add(entry);
        }
        Instant transactionTime = job.getStartedAt() != null
            ? job.getStartedAt() : job.getRequestedAt();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionTime", transactionTime == null ? null : transactionTime.toString());
        body.put("request", job.getRequestUrl() == null ? "" : job.getRequestUrl());
        body.put("requiresAccessToken", true);
        body.put("output", output);
        body.put("error", List.of());
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(write(body));
    }

    private static ResponseEntity<String> failed(FhirBulkExportJob job) {
        String message = job.getErrorMessage() == null
            ? "The bulk-export job failed." : job.getErrorMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.valueOf(FHIR_JSON))
            .body(operationOutcome("exception", message));
    }

    private static ResponseEntity<String> notImplemented() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .contentType(MediaType.valueOf(FHIR_JSON))
            .body(operationOutcome("not-supported",
                "FHIR $export is disabled — set "
                    + "app.fhir.operations.bulk-export.enabled=true to opt in."));
    }

    private static ResponseEntity<String> notFound(UUID jobId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.valueOf(FHIR_JSON))
            .body(operationOutcome("not-found",
                "No bulk-export job " + jobId + " found at the active hospital scope."));
    }

    /** A FHIR OperationOutcome carrying one error issue — serialised, not built by hand. */
    private static String operationOutcome(String code, String diagnostics) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", "error");
        issue.put("code", code);
        issue.put("diagnostics", diagnostics);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("resourceType", "OperationOutcome");
        outcome.put("issue", List.of(issue));
        return write(outcome);
    }

    private static String write(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            // A map of strings, numbers and booleans cannot fail to serialise.
            // If it somehow does, emit a valid minimal outcome rather than a
            // truncated body the client would fail to parse.
            return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{"
                + "\"severity\":\"error\",\"code\":\"exception\","
                + "\"diagnostics\":\"Response could not be serialised.\"}]}";
        }
    }
}
