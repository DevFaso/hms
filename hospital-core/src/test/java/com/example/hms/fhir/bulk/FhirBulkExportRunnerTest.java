package com.example.hms.fhir.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import com.example.hms.fhir.FhirOperationsProperties;
import com.example.hms.fhir.mapper.ConditionFhirMapper;
import com.example.hms.fhir.mapper.EncounterFhirMapper;
import com.example.hms.fhir.mapper.MedicationRequestFhirMapper;
import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.platform.FhirBulkExportFile;
import com.example.hms.model.platform.FhirBulkExportJob;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.FhirBulkExportFileRepository;
import com.example.hms.repository.FhirBulkExportJobRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The NDJSON runner (P3 #24): claims QUEUED jobs atomically, writes one
 * file per resource type as it pages, records the manifest rows, and
 * turns failure / cancellation into a clean terminal state instead of
 * a stuck job.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FhirBulkExportRunnerTest {

    @Mock private FhirBulkExportService service;
    @Mock private FhirBulkExportJobRepository jobRepository;
    @Mock private FhirBulkExportFileRepository fileRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private PatientVitalSignRepository vitalSignRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private PatientProblemRepository patientProblemRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private PatientFhirMapper patientMapper;
    @Mock private EncounterFhirMapper encounterMapper;
    @Mock private ObservationFhirMapper observationMapper;
    @Mock private ConditionFhirMapper conditionMapper;
    @Mock private MedicationRequestFhirMapper medicationRequestMapper;
    @Mock private PlatformTransactionManager transactionManager;

    @TempDir
    Path tempDir;

    private FhirBulkExportRunner runner;
    private FhirBulkExportJob job;
    private UUID jobId;
    private UUID hospitalId;
    private Patient patient;

    @BeforeEach
    void setUp() {
        FhirOperationsProperties properties = new FhirOperationsProperties();
        properties.getBulkExport().setEnabled(true);
        properties.getBulkExport().setStorageDir(tempDir.toString());

        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        runner = new FhirBulkExportRunner(
            service, jobRepository, fileRepository, registrationRepository,
            encounterRepository, vitalSignRepository, labResultRepository,
            patientProblemRepository, prescriptionRepository,
            patientMapper, encounterMapper, observationMapper,
            conditionMapper, medicationRequestMapper,
            FhirContext.forR4(), transactionManager, properties);

        hospitalId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        job = FhirBulkExportJob.builder()
            .hospitalId(hospitalId)
            .scope(FhirBulkExportJob.Scope.SYSTEM)
            .status(FhirBulkExportJob.Status.QUEUED)
            .requestedAt(Instant.now())
            .build();
        job.setId(jobId);

        patient = new Patient();
        patient.setId(UUID.randomUUID());

        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setPatient(patient);

        when(service.isEnabled()).thenReturn(true);
        when(jobRepository.claimQueued(eq(jobId), any())).thenReturn(1);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(FhirBulkExportJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileRepository.save(any(FhirBulkExportFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(registrationRepository.countByHospitalIdAndActiveTrue(hospitalId)).thenReturn(1L);
        when(registrationRepository.findByHospitalIdAndActiveTrue(eq(hospitalId), any()))
            .thenReturn(new PageImpl<>(List.of(registration)));

        stubEmptyClinicalData();

        org.hl7.fhir.r4.model.Patient fhirPatient = new org.hl7.fhir.r4.model.Patient();
        fhirPatient.setId(patient.getId().toString());
        when(patientMapper.toFhir(patient)).thenReturn(fhirPatient);
    }

    private void stubEmptyClinicalData() {
        when(encounterRepository.findByPatient_IdAndHospital_Id(any(), any(), any()))
            .thenReturn(emptyPage());
        when(vitalSignRepository.findPageByPatient_IdAndHospital_IdOrderByRecordedAtDesc(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of()));
        when(labResultRepository.findPageByLabOrder_Patient_IdAndLabOrder_Hospital_Id(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of()));
        when(patientProblemRepository.findByPatient_IdAndHospital_Id(any(), any()))
            .thenReturn(List.of());
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Page emptyPage() {
        return new PageImpl<>(List.of());
    }

    @Test
    void completesAJobAndWritesNdjson() throws Exception {
        runner.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(FhirBulkExportJob.Status.COMPLETED);
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getProcessedPatients()).isEqualTo(1);

        Path patientFile = tempDir.resolve(jobId.toString()).resolve("Patient.ndjson");
        assertThat(patientFile).exists();
        List<String> lines = Files.readAllLines(patientFile);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("\"resourceType\":\"Patient\"");

        ArgumentCaptor<FhirBulkExportFile> captor = ArgumentCaptor.forClass(FhirBulkExportFile.class);
        verify(fileRepository).save(captor.capture());
        assertThat(captor.getValue().getResourceType()).isEqualTo("Patient");
        assertThat(captor.getValue().getFileName()).isEqualTo("Patient.ndjson");
        assertThat(captor.getValue().getResourceCount()).isEqualTo(1);
    }

    @Test
    void emptyTypesNeverLeaveFilesBehind() {
        runner.processJob(jobId);

        // Only the Patient had data; the other four types must not exist
        // as zero-byte files nor as manifest rows.
        Path jobDir = tempDir.resolve(jobId.toString());
        assertThat(jobDir.resolve("Encounter.ndjson")).doesNotExist();
        assertThat(jobDir.resolve("Observation.ndjson")).doesNotExist();
        verify(fileRepository, never()).save(
            org.mockito.ArgumentMatchers.argThat(f -> !"Patient".equals(f.getResourceType())));
    }

    @Test
    void honoursTheTypeFilter() {
        job.setTypes("Encounter");

        runner.processJob(jobId);

        assertThat(tempDir.resolve(jobId.toString()).resolve("Patient.ndjson")).doesNotExist();
        verify(patientMapper, never()).toFhir(any(Patient.class));
    }

    @Test
    void aSinceFilterExcludesOlderRows() {
        patient.setUpdatedAt(java.time.LocalDateTime.of(2020, 1, 1, 0, 0));
        job.setSinceInstant(Instant.parse("2026-01-01T00:00:00Z"));

        runner.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(FhirBulkExportJob.Status.COMPLETED);
        assertThat(tempDir.resolve(jobId.toString()).resolve("Patient.ndjson")).doesNotExist();
    }

    @Test
    void anUnclaimedJobIsLeftAlone() {
        when(jobRepository.claimQueued(eq(jobId), any())).thenReturn(0);

        runner.processJob(jobId);

        verify(registrationRepository, never()).findByHospitalIdAndActiveTrue(any(), any());
    }

    @Test
    void aFailureMarksTheJobFailedAndCleansUp() {
        when(registrationRepository.findByHospitalIdAndActiveTrue(eq(hospitalId), any()))
            .thenThrow(new RuntimeException("db down"));

        runner.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(FhirBulkExportJob.Status.FAILED);
        assertThat(job.getErrorMessage()).contains("db down");
        assertThat(tempDir.resolve(jobId.toString())).doesNotExist();
    }

    @Test
    void aCancelledJobAbortsAndDiscardsOutput() {
        // The status flips to CANCELLED underneath the runner (DELETE on
        // the status endpoint mid-run).
        when(jobRepository.findById(jobId))
            .thenReturn(Optional.of(job))
            .thenAnswer(inv -> {
                job.setStatus(FhirBulkExportJob.Status.CANCELLED);
                return Optional.of(job);
            });

        runner.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(FhirBulkExportJob.Status.CANCELLED);
        assertThat(tempDir.resolve(jobId.toString())).doesNotExist();
        verify(fileRepository, never()).save(any());
    }

    @Test
    void theSweepDrivesQueuedJobsToCompletion() {
        when(jobRepository.findByStatusOrderByRequestedAtAsc(FhirBulkExportJob.Status.QUEUED))
            .thenReturn(List.of(job));

        runner.runSweep();

        assertThat(job.getStatus()).isEqualTo(FhirBulkExportJob.Status.COMPLETED);
    }

    @Test
    void flagOffMeansTheSweepDoesNothing() {
        when(service.isEnabled()).thenReturn(false);

        runner.runSweep();

        verify(jobRepository, never()).findByStatusOrderByRequestedAtAsc(any());
    }
}
