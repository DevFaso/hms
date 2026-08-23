package com.example.hms.fhir.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.hms.model.platform.FhirBulkExportFile;
import com.example.hms.model.platform.FhirBulkExportJob;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The poll surface finally has all its spec branches (P3 #24): 202
 * while running, 200 + manifest on completion, 500 + OperationOutcome
 * on failure, 404 for cancelled/unknown, 501 when the flag is off.
 */
@ExtendWith(MockitoExtension.class)
class FhirBulkExportStatusControllerTest {

    @Mock private FhirBulkExportService service;

    private FhirBulkExportStatusController controller;

    private UUID jobId;
    private FhirBulkExportJob job;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        controller = new FhirBulkExportStatusController(service);
        lenient().when(service.isEnabled()).thenReturn(true);

        jobId = UUID.randomUUID();
        job = FhirBulkExportJob.builder()
            .hospitalId(UUID.randomUUID())
            .scope(FhirBulkExportJob.Scope.SYSTEM)
            .status(FhirBulkExportJob.Status.IN_PROGRESS)
            .requestUrl("/api/fhir/$export")
            .requestedAt(Instant.parse("2026-08-22T10:00:00Z"))
            .startedAt(Instant.parse("2026-08-22T10:01:00Z"))
            .build();
        job.setId(jobId);
        lenient().when(service.getJob(jobId)).thenReturn(Optional.of(job));

        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/fhir-bulk-status/" + jobId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aRunningJobPollsAs202WithRealProgress() {
        job.setProcessedPatients(40);
        job.setTotalPatients(120);

        ResponseEntity<String> response = controller.getStatus(jobId);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getFirst("X-Progress")).contains("40/120");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("120");
    }

    @Test
    void aCompletedJobServesTheManifest() {
        job.setStatus(FhirBulkExportJob.Status.COMPLETED);
        when(service.filesFor(jobId)).thenReturn(List.of(
            FhirBulkExportFile.builder()
                .job(job).resourceType("Patient").fileName("Patient.ndjson").resourceCount(12)
                .build()));

        ResponseEntity<String> response = controller.getStatus(jobId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"transactionTime\":\"2026-08-22T10:01:00Z\"");
        assertThat(body).contains("\"request\":\"/api/fhir/$export\"");
        assertThat(body).contains("\"requiresAccessToken\":true");
        assertThat(body).contains("\"type\":\"Patient\"");
        assertThat(body).contains("/file/Patient.ndjson");
        assertThat(body).contains("\"count\":12");
        assertThat(body).contains("\"error\":[]");
    }

    @Test
    void aFailedJobIs500WithTheFailureMessage() {
        job.setStatus(FhirBulkExportJob.Status.FAILED);
        job.setErrorMessage("db down");

        ResponseEntity<String> response = controller.getStatus(jobId);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).contains("db down");
    }

    @Test
    void aCancelledJobPollsAs404PerTheSpec() {
        job.setStatus(FhirBulkExportJob.Status.CANCELLED);

        ResponseEntity<String> response = controller.getStatus(jobId);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void flagOffIs501() {
        when(service.isEnabled()).thenReturn(false);

        assertThat(controller.getStatus(jobId).getStatusCode().value()).isEqualTo(501);
        assertThat(controller.cancel(jobId).getStatusCode().value()).isEqualTo(501);
        assertThat(controller.download(jobId, "Patient.ndjson").getStatusCode().value()).isEqualTo(501);
    }

    @Test
    void cancelIs202OnAnOpenJobAnd404Otherwise() {
        when(service.cancelExport(jobId)).thenReturn(true);
        assertThat(controller.cancel(jobId).getStatusCode().value()).isEqualTo(202);

        when(service.cancelExport(jobId)).thenReturn(false);
        assertThat(controller.cancel(jobId).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void downloadStreamsACompletedJobsFile() throws Exception {
        job.setStatus(FhirBulkExportJob.Status.COMPLETED);
        Path file = tempDir.resolve("Patient.ndjson");
        Files.writeString(file, "{\"resourceType\":\"Patient\"}\n");
        when(service.resolveOutputFile(job, "Patient.ndjson")).thenReturn(file);

        ResponseEntity<Resource> response = controller.download(jobId, "Patient.ndjson");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString())
            .isEqualTo("application/fhir+ndjson");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().contentLength()).isGreaterThan(0);
    }

    @Test
    void downloadRefusesAJobThatIsNotCompleted() {
        job.setStatus(FhirBulkExportJob.Status.IN_PROGRESS);

        assertThat(controller.download(jobId, "Patient.ndjson").getStatusCode().value())
            .isEqualTo(404);
    }

    @Test
    void unknownJobsAre404() {
        UUID stranger = UUID.randomUUID();
        when(service.getJob(stranger)).thenReturn(Optional.empty());

        assertThat(controller.getStatus(stranger).getStatusCode().value()).isEqualTo(404);
    }
}
