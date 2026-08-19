package com.example.hms.fhir.bulk;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring controller serving the {@code Content-Location} URL returned
 * by {@link FhirBulkExportOperationProvider#exportSystem} /
 * {@code exportPatient}. Mounted at {@code /api/fhir-bulk-status/{jobId}}
 * (sibling to HAPI's {@code /api/fhir/*} servlet) because the FHIR
 * servlet captures the entire {@code /api/fhir/*} space — the
 * canonical mounting via a HAPI plain-provider {@code @Operation}
 * with {@code manualResponse=true} is on the row-21 follow-on once
 * the async runner lands.
 *
 * <p>Foundation-pass semantics:
 * <ul>
 *   <li>{@code GET /api/fhir-bulk-status/{id}} returns
 *       {@code 202 Accepted} (per the FHIR bulk-data spec for the
 *       "still in progress" state) until the actual runner lands.
 *       A non-existent or cross-tenant job returns {@code 404 Not Found}
 *       (cross-tenant rejection is invisible).</li>
 *   <li>{@code DELETE /api/fhir-bulk-status/{id}} cancels the job —
 *       {@code 202 Accepted} on hit, {@code 404 Not Found} otherwise.</li>
 * </ul>
 *
 * <p>The {@code 200 OK + manifest} branch lands with the row-21
 * follow-on once the runner can emit NDJSON to the S3-compatible
 * bucket.
 */
@RestController
@RequestMapping("/fhir-bulk-status")
public class FhirBulkExportStatusController {

    private static final String FHIR_JSON = "application/fhir+json";

    private final FhirBulkExportService service;

    public FhirBulkExportStatusController(FhirBulkExportService service) {
        this.service = service;
    }

    @GetMapping(value = "/{jobId}", produces = FHIR_JSON)
    public ResponseEntity<String> getStatus(@PathVariable UUID jobId) {
        if (!service.isEnabled()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.valueOf(FHIR_JSON))
                .body(notSupportedOutcome());
        }
        Optional<BulkExportJobState> state = service.getJob(jobId);
        if (state.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.valueOf(FHIR_JSON))
                .body(notFoundOutcome(jobId));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Progress", "in-progress (foundation pass — runner deferred)");
        headers.add("Retry-After", "120");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .headers(headers)
            .build();
    }

    @DeleteMapping(value = "/{jobId}", produces = FHIR_JSON)
    public ResponseEntity<String> cancel(@PathVariable UUID jobId) {
        if (!service.isEnabled()) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.valueOf(FHIR_JSON))
                .body(notSupportedOutcome());
        }
        boolean removed = service.cancelExport(jobId);
        if (!removed) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.valueOf(FHIR_JSON))
                .body(notFoundOutcome(jobId));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private static String notSupportedOutcome() {
        return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{"
            + "\"severity\":\"error\",\"code\":\"not-supported\","
            + "\"diagnostics\":\"FHIR $export is disabled — set "
            + "app.fhir.operations.bulk-export.enabled=true to opt in.\""
            + "}]}";
    }

    private static String notFoundOutcome(UUID jobId) {
        return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{"
            + "\"severity\":\"error\",\"code\":\"not-found\","
            + "\"diagnostics\":\"No bulk-export job " + jobId
            + " found at the active hospital scope.\""
            + "}]}";
    }
}
