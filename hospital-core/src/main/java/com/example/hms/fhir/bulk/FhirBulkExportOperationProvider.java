package com.example.hms.fhir.bulk;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import com.example.hms.fhir.bulk.FhirBulkExportService.Scope;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.UriType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
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
        // engaged in manualResponse mode, so we write JSON directly.
        try {
            response.setContentType("application/fhir+json");
            Parameters params = new Parameters();
            params.addParameter().setName("jobId").setValue(new StringType(state.getJobId().toString()));
            params.addParameter().setName("pollUrl").setValue(new UriType(STATUS_PATH_PREFIX + state.getJobId()));
            String body = "{\"resourceType\":\"Parameters\",\"parameter\":["
                + "{\"name\":\"jobId\",\"valueString\":\"" + state.getJobId() + "\"},"
                + "{\"name\":\"pollUrl\",\"valueUri\":\"" + STATUS_PATH_PREFIX + state.getJobId() + "\"}"
                + "]}";
            response.getWriter().write(body);
            response.getWriter().flush();
        } catch (java.io.IOException ex) {
            // 202 + Content-Location is already on the wire; body emit
            // failure is a logging-only concern.
            OperationOutcome unused = new OperationOutcome();
            unused.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.WARNING)
                .setCode(OperationOutcome.IssueType.PROCESSING)
                .setDiagnostics("Failed to write kickoff body: " + ex.getMessage());
        }
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static List<String> parseTypeList(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        return new ArrayList<>(Arrays.asList(raw.split("\\s*,\\s*")));
    }
}
