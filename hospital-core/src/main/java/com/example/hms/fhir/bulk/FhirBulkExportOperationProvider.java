package com.example.hms.fhir.bulk;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.example.hms.fhir.bulk.FhirBulkExportService.Scope;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * HAPI plain provider exposing FHIR R4 Bulk Data Access {@code $export}
 * (roadmap row 21, v1.1 / Backend / Interop FHIR). Registered on the
 * {@code RestfulServer} via {@code FhirConfig#fhirServletRegistration}.
 *
 * <p>Operations supported in the foundation pass:
 * <ul>
 *   <li>{@code POST /api/fhir/$export} — system-level export</li>
 *   <li>{@code POST /api/fhir/Patient/$export} — Patient-type-level export</li>
 * </ul>
 *
 * <p>Group-level {@code /api/fhir/Group/[id]/$export} is deferred to
 * the row-21 follow-on (needs a {@code GroupFhirResourceProvider},
 * which HMS does not have yet).
 *
 * <p>Each accepted request returns
 * {@link HttpServletResponse#SC_ACCEPTED 202 Accepted} with a
 * {@code Content-Location} header pointing at
 * {@code /api/fhir-bulk-status/{jobId}} (handled by
 * {@link FhirBulkExportStatusController}). The
 * {@code /api/fhir-bulk-status/...} sibling path is an HMS-specific
 * mount because Spring's FHIR servlet captures the entire
 * {@code /api/fhir/*} space — the canonical
 * {@code /api/fhir/$export-poll-status/{id}} mounting via a HAPI
 * {@code @Operation} with {@code manualResponse=true} is on the
 * row-21 follow-on once the async runner lands.
 *
 * <p>Flag-off behaviour: every call throws
 * {@code MethodNotAllowedException} from
 * {@link FhirBulkExportService#createExport} so the response is 405 +
 * a FHIR {@code OperationOutcome} (NOTSUPPORTED) — matching the
 * established HMS flag-off contract on the FHIR write paths.
 */
@Component
public class FhirBulkExportOperationProvider {

    private static final Logger log = LoggerFactory.getLogger(FhirBulkExportOperationProvider.class);
    private static final String CONTENT_LOCATION_HEADER = "Content-Location";
    private static final String STATUS_PATH_PREFIX = "/api/fhir-bulk-status/";

    private final FhirBulkExportService service;

    public FhirBulkExportOperationProvider(FhirBulkExportService service) {
        this.service = service;
    }

    /**
     * System-level {@code $export}. Returns 202 + {@code Content-Location}
     * pointing at the poll endpoint. The response body is a minimal
     * {@code Parameters} resource carrying the assigned job id —
     * non-spec but useful for clients that want the id without
     * parsing the header.
     */
    @Operation(name = "$export", idempotent = false, manualResponse = true)
    public void exportSystem(
        @OperationParam(name = "_since") String since,
        @OperationParam(name = "_type") String type,
        @OperationParam(name = "_outputFormat") String outputFormat,
        HttpServletResponse response
    ) {
        startExport(Scope.SYSTEM, since, type, null, response);
    }

    /**
     * Patient-type-level {@code $export}. Same shape as system-level;
     * the {@code Scope.PATIENT} tag is recorded on the job so the
     * follow-on runner can scope its query.
     */
    @Operation(name = "$export", idempotent = false, manualResponse = true,
        type = Patient.class)
    public void exportPatient(
        @OperationParam(name = "_since") String since,
        @OperationParam(name = "_type") String type,
        @OperationParam(name = "_outputFormat") String outputFormat,
        HttpServletResponse response
    ) {
        startExport(Scope.PATIENT, since, type, null, response);
    }

    private void startExport(
        Scope scope, String sinceRaw, String typeRaw, String groupId,
        HttpServletResponse response
    ) {
        Instant since = parseInstant(sinceRaw);
        List<String> types = parseTypeList(typeRaw);
        BulkExportJobState state = service.createExport(scope, since, types, groupId);

        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.setHeader(CONTENT_LOCATION_HEADER,
            STATUS_PATH_PREFIX + state.getJobId());

        // Best-effort minimal Parameters body. The bulk-data spec does
        // not mandate a response body on the kickoff 202 — most
        // implementations return an OperationOutcome or empty body —
        // but emitting the job id makes the foundation pass observable
        // without spec deviation. HAPI's response renderer is not
        // engaged in manualResponse mode, so we write JSON directly
        // (PR #352 Copilot review — dropped the unused
        // Parameters/StringType/UriType construction that built a
        // model resource and then discarded it).
        try {
            response.setContentType("application/fhir+json");
            String body = "{\"resourceType\":\"Parameters\",\"parameter\":["
                + "{\"name\":\"jobId\",\"valueString\":\"" + state.getJobId() + "\"},"
                + "{\"name\":\"pollUrl\",\"valueUri\":\"" + STATUS_PATH_PREFIX + state.getJobId() + "\"}"
                + "]}";
            response.getWriter().write(body);
            response.getWriter().flush();
        } catch (java.io.IOException ex) {
            // 202 + Content-Location is already on the wire; body emit
            // failure is a logging-only concern.
            log.warn("Failed to write $export kickoff body for job {}: {}",
                state.getJobId(), ex.toString());
        }
    }

    /**
     * Parse the FHIR Bulk Data Access {@code _since} parameter.
     * Returns {@code null} for blank input (no filter); throws
     * {@link InvalidRequestException} (HAPI → HTTP 400) for malformed
     * input. The bulk-data spec requires rejection of malformed
     * {@code _since} so clients don't believe an incremental window
     * held when the runner actually fell back to a full export (PR
     * #352 Copilot review — Medium).
     */
    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException ex) {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.VALUE)
                .setDiagnostics("_since must be an ISO-8601 instant (e.g. 2026-01-01T00:00:00Z); got '"
                    + raw + "'.");
            throw new InvalidRequestException(
                "Invalid _since value: " + raw, outcome
            );
        }
    }

    /**
     * Parse the FHIR Bulk Data Access {@code _type} comma-separated
     * list. Safe form (PR #352 Copilot/Sonar — the previous
     * {@code split("\s*,\s*")} regex was flagged as
     * polynomial-backtracking ReDoS): split on the literal comma, then
     * trim each token and filter empties.
     */
    private static List<String> parseTypeList(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
