package com.example.hms.fhir.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import com.example.hms.fhir.FhirOperationsProperties;
import com.example.hms.model.platform.FhirBulkExportJob;
import com.example.hms.repository.FhirBulkExportFileRepository;
import com.example.hms.repository.FhirBulkExportJobRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Persistent bulk-export job store (P3 #24): tenant pinning, role gate,
 * kickoff validation, and the deactivate-never-delete cancel contract.
 */
@ExtendWith(MockitoExtension.class)
class FhirBulkExportServiceTest {

    @Mock private AuditEventLogService auditEventLogService;
    @Mock private FhirBulkExportJobRepository jobRepository;
    @Mock private FhirBulkExportFileRepository fileRepository;
    @Mock private RoleValidator roleValidator;

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path storeDir;

    private FhirOperationsProperties properties;
    private FhirBulkExportService service;

    private UUID hospitalId;

    @BeforeEach
    void setUp() {
        properties = new FhirOperationsProperties();
        properties.getBulkExport().setEnabled(true);
        service = new FhirBulkExportService(
            properties, auditEventLogService, jobRepository, fileRepository, roleValidator,
            new com.example.hms.storage.LocalFileStorage(storeDir));

        hospitalId = UUID.randomUUID();
        HospitalContextHolder.setContext(
            HospitalContext.builder().activeHospitalId(hospitalId).build());
        lenient().when(roleValidator.hasAnyAuthority("SUPER_ADMIN", "HOSPITAL_ADMIN"))
            .thenReturn(true);
        lenient().when(jobRepository.save(any(FhirBulkExportJob.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    void createPinsTheJobToTheActiveHospital() {
        FhirBulkExportJob job = service.createExport(
            FhirBulkExportJob.Scope.SYSTEM, Instant.parse("2026-01-01T00:00:00Z"),
            List.of("Patient", "Observation"), "application/fhir+ndjson", null,
            "/api/fhir/$export?_type=Patient,Observation");

        assertThat(job.getHospitalId()).isEqualTo(hospitalId);
        assertThat(job.getStatus()).isEqualTo(FhirBulkExportJob.Status.QUEUED);
        assertThat(job.typeList()).containsExactly("Patient", "Observation");
        assertThat(job.getRequestUrl()).contains("$export");
    }

    @Test
    void createRefusesWithoutAnActiveHospital() {
        // The foundation pass created a null-tenant job here — a row the
        // deny-on-null lookup could never return again.
        HospitalContextHolder.clear();

        assertThatThrownBy(() -> service.createExport(
            FhirBulkExportJob.Scope.SYSTEM, null, List.of(), null, null, "/api/fhir/$export"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("active hospital");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void createIsAMassPhiExtractAndRequiresAnAdminRole() {
        when(roleValidator.hasAnyAuthority("SUPER_ADMIN", "HOSPITAL_ADMIN")).thenReturn(false);

        assertThatThrownBy(() -> service.createExport(
            FhirBulkExportJob.Scope.SYSTEM, null, List.of(), null, null, "/api/fhir/$export"))
            .isInstanceOf(ForbiddenOperationException.class);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void createRejectsAnUnsupportedType() {
        assertThatThrownBy(() -> service.createExport(
            FhirBulkExportJob.Scope.SYSTEM, null, List.of("DiagnosticReport"), null, null, "x"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("DiagnosticReport");
    }

    @Test
    void createRejectsAnUnsupportedOutputFormat() {
        assertThatThrownBy(() -> service.createExport(
            FhirBulkExportJob.Scope.SYSTEM, null, List.of(), "text/csv", null, "x"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("_outputFormat");
    }

    @Test
    void flagOffIs501PerTheBulkDataSpec() {
        properties.getBulkExport().setEnabled(false);

        assertThatThrownBy(() -> service.createExport(
            FhirBulkExportJob.Scope.SYSTEM, null, List.of(), null, null, "x"))
            .isInstanceOf(NotImplementedOperationException.class);
    }

    @Test
    void lookupsWithoutAHospitalScopeSeeNothing() {
        // DENY on null context: a super-admin without X-Hospital-Id must
        // not see every tenant's jobs.
        HospitalContextHolder.clear();

        assertThat(service.getJob(UUID.randomUUID())).isEmpty();
        verify(jobRepository, never()).findByIdAndHospitalId(any(), any());
    }

    @Test
    void cancelKeepsTheRowButFlipsItCancelled() {
        FhirBulkExportJob job = FhirBulkExportJob.builder()
            .hospitalId(hospitalId)
            .scope(FhirBulkExportJob.Scope.SYSTEM)
            .status(FhirBulkExportJob.Status.QUEUED)
            .build();
        job.setId(UUID.randomUUID());
        when(jobRepository.findByIdAndHospitalId(job.getId(), hospitalId))
            .thenReturn(Optional.of(job));

        assertThat(service.cancelExport(job.getId())).isTrue();
        assertThat(job.getStatus()).isEqualTo(FhirBulkExportJob.Status.CANCELLED);
        assertThat(job.getCompletedAt()).isNotNull();
        verify(jobRepository).save(job);
        verify(jobRepository, never()).delete(any());
    }

    @Test
    void aTerminalJobIsNotCancellable() {
        FhirBulkExportJob job = FhirBulkExportJob.builder()
            .hospitalId(hospitalId)
            .scope(FhirBulkExportJob.Scope.SYSTEM)
            .status(FhirBulkExportJob.Status.COMPLETED)
            .build();
        job.setId(UUID.randomUUID());
        when(jobRepository.findByIdAndHospitalId(job.getId(), hospitalId))
            .thenReturn(Optional.of(job));

        assertThat(service.cancelExport(job.getId())).isFalse();
        verify(jobRepository, never()).save(any());
    }

    @Test
    void openOutputFileReadsThePublishedFileOfTheRecordedRow() throws Exception {
        FhirBulkExportJob job = FhirBulkExportJob.builder().hospitalId(hospitalId).build();
        job.setId(UUID.randomUUID());
        when(fileRepository.findByJob_IdAndFileName(job.getId(), "Patient.ndjson")).thenReturn(Optional.of(
            com.example.hms.model.platform.FhirBulkExportFile.builder().job(job).resourceType("Patient")
                .fileName("Patient.ndjson").resourceCount(1).build()));
        java.nio.file.Path published = storeDir.resolve("fhir-bulk-exports").resolve(job.getId().toString());
        java.nio.file.Files.createDirectories(published);
        java.nio.file.Files.writeString(published.resolve("Patient.ndjson"), "{}");

        try (com.example.hms.storage.StoredObject stored = service.openOutputFile(job, "Patient.ndjson")) {
            assertThat(new String(stored.content().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{}");
        }
    }

    @Test
    void openOutputFileIsNotFoundWithoutARowOrWithoutTheObject() {
        FhirBulkExportJob job = FhirBulkExportJob.builder().hospitalId(hospitalId).build();
        job.setId(UUID.randomUUID());
        when(fileRepository.findByJob_IdAndFileName(job.getId(), "Patient.ndjson")).thenReturn(Optional.of(
            com.example.hms.model.platform.FhirBulkExportFile.builder().job(job).resourceType("Patient")
                .fileName("Patient.ndjson").resourceCount(1).build()));
        when(fileRepository.findByJob_IdAndFileName(job.getId(), "../x.ndjson")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openOutputFile(job, "Patient.ndjson"))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.openOutputFile(job, "../x.ndjson"))
            .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
    }
}
