package com.example.hms.fhir.bulk;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.example.hms.fhir.FhirOperationsProperties;
import com.example.hms.fhir.mapper.ConditionFhirMapper;
import com.example.hms.fhir.mapper.EncounterFhirMapper;
import com.example.hms.fhir.mapper.MedicationRequestFhirMapper;
import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.platform.FhirBulkExportJob;
import com.example.hms.model.platform.FhirBulkExportFile;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.FhirBulkExportFileRepository;
import com.example.hms.repository.FhirBulkExportJobRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The async half of FHIR Bulk Data Access (P3 #24, roadmap row 21
 * follow-on): a thin {@code @Scheduled} sweep — the house pattern
 * (DhisAdxScheduler / AppointmentReminderScheduler), deliberately NOT
 * {@code @Async}, because the codebase has no {@code @EnableAsync} and
 * adding it would silently activate the one dormant {@code @Async}
 * method elsewhere — that claims QUEUED jobs atomically and streams
 * NDJSON to local disk, one file per resource type, writing as it
 * pages so a large tenant never has to fit in memory.
 *
 * <p>Output lands under
 * {@code app.fhir.operations.bulk-export.storage-dir}/{jobId}/ — a
 * sibling of the public upload tree (V126 patient-photo precedent),
 * never under permitAll {@code /uploads/**}. Files reach clients only
 * through the authenticated download endpoint on
 * {@link FhirBulkExportStatusController}.
 *
 * <p>Failure marks the job FAILED with the message and removes partial
 * output; a job whose row flips to CANCELLED mid-run is noticed at the
 * next patient page and aborted the same way. No automatic retry — an
 * operator re-kicks $export, which is idempotent by construction (each
 * job has its own directory).
 */
@Component
public class FhirBulkExportRunner {

    private static final Logger log = LoggerFactory.getLogger(FhirBulkExportRunner.class);

    // FHIR resource-type names — the wire identity of each NDJSON file, and
    // the same tokens the caller passes in `_type`. Named so a typo can't
    // silently split "the type we filter on" from "the file we write to".
    private static final String TYPE_PATIENT = "Patient";
    private static final String TYPE_ENCOUNTER = "Encounter";
    private static final String TYPE_OBSERVATION = "Observation";
    private static final String TYPE_CONDITION = "Condition";
    private static final String TYPE_MEDICATION_REQUEST = "MedicationRequest";

    /** Emission order — Patient first so consumers can resolve references. */
    private static final List<String> TYPE_ORDER = List.of(
        TYPE_PATIENT, TYPE_ENCOUNTER, TYPE_OBSERVATION, TYPE_CONDITION, TYPE_MEDICATION_REQUEST);

    private static final int PATIENT_PAGE_SIZE = 100;
    private static final int RESOURCE_PAGE_SIZE = 200;

    private final FhirBulkExportService service;
    private final FhirBulkExportJobRepository jobRepository;
    private final FhirBulkExportFileRepository fileRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final EncounterRepository encounterRepository;
    private final PatientVitalSignRepository vitalSignRepository;
    private final LabResultRepository labResultRepository;
    private final PatientProblemRepository patientProblemRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final PatientFhirMapper patientMapper;
    private final EncounterFhirMapper encounterMapper;
    private final ObservationFhirMapper observationMapper;
    private final ConditionFhirMapper conditionMapper;
    private final MedicationRequestFhirMapper medicationRequestMapper;
    private final FhirContext fhirContext;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final Path storageRoot;

    @SuppressWarnings("java:S107") // orchestrator wiring — one dependency per resource type
    public FhirBulkExportRunner(
        FhirBulkExportService service,
        FhirBulkExportJobRepository jobRepository,
        FhirBulkExportFileRepository fileRepository,
        PatientHospitalRegistrationRepository registrationRepository,
        EncounterRepository encounterRepository,
        PatientVitalSignRepository vitalSignRepository,
        LabResultRepository labResultRepository,
        PatientProblemRepository patientProblemRepository,
        PrescriptionRepository prescriptionRepository,
        PatientFhirMapper patientMapper,
        EncounterFhirMapper encounterMapper,
        ObservationFhirMapper observationMapper,
        ConditionFhirMapper conditionMapper,
        MedicationRequestFhirMapper medicationRequestMapper,
        FhirContext fhirContext,
        PlatformTransactionManager transactionManager,
        FhirOperationsProperties operationsProperties
    ) {
        this.service = service;
        this.jobRepository = jobRepository;
        this.fileRepository = fileRepository;
        this.registrationRepository = registrationRepository;
        this.encounterRepository = encounterRepository;
        this.vitalSignRepository = vitalSignRepository;
        this.labResultRepository = labResultRepository;
        this.patientProblemRepository = patientProblemRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.patientMapper = patientMapper;
        this.encounterMapper = encounterMapper;
        this.observationMapper = observationMapper;
        this.conditionMapper = conditionMapper;
        this.medicationRequestMapper = medicationRequestMapper;
        this.fhirContext = fhirContext;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
        this.storageRoot = Paths.get(
            operationsProperties.getBulkExport().getStorageDir()).toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelayString = "${app.fhir.operations.bulk-export.runner-interval-ms:60000}")
    public void runSweep() {
        if (!service.isEnabled()) {
            return; // flag off — the kickoff can't queue jobs either
        }
        try {
            List<FhirBulkExportJob> queued =
                jobRepository.findByStatusOrderByRequestedAtAsc(FhirBulkExportJob.Status.QUEUED);
            for (FhirBulkExportJob job : queued) {
                processJob(job.getId());
            }
        } catch (RuntimeException ex) {
            // Never propagate: an escaped exception cancels the whole
            // fixed-delay schedule in Spring.
            log.error("FHIR bulk-export sweep failed: {}", ex.getMessage(), ex);
        }
    }

    /** Claim and run one job. Package-visible so tests can drive it directly. */
    void processJob(UUID jobId) {
        Integer claimed = writeTx.execute(s -> jobRepository.claimQueued(jobId, Instant.now()));
        if (claimed == null || claimed == 0) {
            return; // another instance won the claim, or the job moved on
        }
        FhirBulkExportJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        Path jobDir = storageRoot.resolve(job.getId().toString()).normalize();
        try {
            export(job, jobDir);
        } catch (Exception ex) {
            log.warn("FHIR bulk-export job {} failed: {}", jobId, ex.getMessage(), ex);
            markFailed(jobId, ex);
            deleteQuietly(jobDir);
        }
    }

    private void export(FhirBulkExportJob job, Path jobDir) throws IOException {
        if (job.getScope() == FhirBulkExportJob.Scope.GROUP) {
            // Kickoff has no Group surface yet; a GROUP row can only come
            // from a future writer — refuse loudly instead of exporting
            // the wrong cohort.
            throw new IllegalStateException("Group-level $export is not implemented.");
        }
        Set<String> types = resolveTypes(job);
        UUID hospitalId = job.getHospitalId();
        long total = registrationRepository.countByHospitalIdAndActiveTrue(hospitalId);
        updateProgress(job.getId(), 0, (int) Math.min(total, Integer.MAX_VALUE));

        Files.createDirectories(jobDir);
        int processed = 0;
        try (NdjsonSink sink = new NdjsonSink(jobDir, fhirContext.newJsonParser())) {
            int pageIdx = 0;
            boolean more = true;
            while (more) {
                PageResult result = exportRegistrationPage(job, types, pageIdx, sink);
                processed += result.patients();
                more = result.hasNext();
                pageIdx++;
                updateProgress(job.getId(), processed, (int) Math.min(total, Integer.MAX_VALUE));
                if (isCancelled(job.getId())) {
                    log.info("FHIR bulk-export job {} cancelled mid-run — discarding output", job.getId());
                    sink.close();
                    deleteQuietly(jobDir);
                    return;
                }
            }
            sink.close();
            recordOutput(job, sink);
        }
        complete(job.getId(), processed);
    }

    private PageResult exportRegistrationPage(
        FhirBulkExportJob job, Set<String> types, int pageIdx, NdjsonSink sink
    ) {
        Instant since = job.getSinceInstant();
        UUID hospitalId = job.getHospitalId();
        Pageable pageable = PageRequest.of(pageIdx, PATIENT_PAGE_SIZE, Sort.by("id"));
        // Mapping walks lazy associations, so the whole page is exported
        // inside one read-only transaction.
        return readTx.execute(status -> {
            Page<PatientHospitalRegistration> registrations =
                registrationRepository.findByHospitalIdAndActiveTrue(hospitalId, pageable);
            registrations.forEach(reg ->
                exportOnePatient(reg.getPatient(), hospitalId, types, since, sink));
            return new PageResult(registrations.getNumberOfElements(), registrations.hasNext());
        });
    }

    private void exportOnePatient(
        Patient patient, UUID hospitalId, Set<String> types, Instant since, NdjsonSink sink
    ) {
        if (patient == null || patient.getId() == null) {
            return;
        }
        UUID patientId = patient.getId();
        if (types.contains(TYPE_PATIENT) && passesSince(patient.getUpdatedAt(), since)) {
            sink.write(TYPE_PATIENT, patientMapper.toFhir(patient));
        }
        if (types.contains(TYPE_ENCOUNTER)) {
            pageThrough(p -> encounterRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId, p),
                e -> {
                    var encounter = (com.example.hms.model.Encounter) e;
                    if (passesSince(encounter.getUpdatedAt(), since)) {
                        sink.write(TYPE_ENCOUNTER, encounterMapper.toFhir(encounter));
                    }
                });
        }
        if (types.contains(TYPE_OBSERVATION)) {
            pageThrough(p -> vitalSignRepository
                    .findPageByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId, p),
                v -> {
                    if (passesSince(v.getUpdatedAt(), since)) {
                        observationMapper.toFhir(v).forEach(o -> sink.write(TYPE_OBSERVATION, o));
                    }
                });
            pageThrough(p -> labResultRepository
                    .findPageByLabOrder_Patient_IdAndLabOrder_Hospital_Id(patientId, hospitalId, p),
                r -> {
                    if (passesSince(r.getUpdatedAt(), since)) {
                        sink.write(TYPE_OBSERVATION, observationMapper.toFhir(r));
                    }
                });
        }
        if (types.contains(TYPE_CONDITION)) {
            patientProblemRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
                .filter(c -> passesSince(c.getUpdatedAt(), since))
                .forEach(c -> sink.write(TYPE_CONDITION, conditionMapper.toFhir(c)));
        }
        if (types.contains(TYPE_MEDICATION_REQUEST)) {
            pageThrough(p -> prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId, p),
                rx -> {
                    if (passesSince(rx.getUpdatedAt(), since)) {
                        sink.write(TYPE_MEDICATION_REQUEST, medicationRequestMapper.toFhir(rx));
                    }
                });
        }
    }

    private static <T> void pageThrough(
        Function<Pageable, Page<T>> query, java.util.function.Consumer<T> consumer
    ) {
        int page = 0;
        Page<T> current;
        do {
            current = query.apply(PageRequest.of(page, RESOURCE_PAGE_SIZE));
            current.forEach(consumer);
            page++;
        } while (current.hasNext());
    }

    private Set<String> resolveTypes(FhirBulkExportJob job) {
        List<String> requested = job.typeList();
        Stream<String> selected = requested.isEmpty()
            ? TYPE_ORDER.stream()
            : TYPE_ORDER.stream().filter(requested::contains);
        return selected.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static boolean passesSince(java.time.LocalDateTime updatedAt, Instant since) {
        if (since == null) return true;
        // Null timestamps pass — dropping them would silently exclude
        // legacy rows (the PatientEverythingParams.afterSince contract).
        if (updatedAt == null) return true;
        return !updatedAt.toInstant(ZoneOffset.UTC).isBefore(since);
    }

    private void recordOutput(FhirBulkExportJob job, NdjsonSink sink) {
        writeTx.executeWithoutResult(s -> sink.counts().forEach((type, count) ->
            fileRepository.save(FhirBulkExportFile.builder()
                .job(job)
                .resourceType(type)
                .fileName(type + ".ndjson")
                .resourceCount(count)
                .build())));
    }

    private void complete(UUID jobId, int processed) {
        writeTx.executeWithoutResult(s -> jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(FhirBulkExportJob.Status.COMPLETED);
            job.setCompletedAt(Instant.now());
            job.setProcessedPatients(processed);
            jobRepository.save(job);
            service.emitAudit(job, "FHIR $export job completed (" + processed + " patient(s))");
        }));
    }

    private void markFailed(UUID jobId, Exception ex) {
        try {
            writeTx.executeWithoutResult(s -> jobRepository.findById(jobId).ifPresent(job -> {
                job.setStatus(FhirBulkExportJob.Status.FAILED);
                job.setCompletedAt(Instant.now());
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                job.setErrorMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
                jobRepository.save(job);
                service.emitAudit(job, "FHIR $export job failed: " + job.getErrorMessage());
            }));
        } catch (RuntimeException inner) {
            log.error("Could not mark FHIR bulk-export job {} FAILED: {}", jobId, inner.toString());
        }
    }

    private void updateProgress(UUID jobId, int processed, int total) {
        writeTx.executeWithoutResult(s -> jobRepository.findById(jobId).ifPresent(job -> {
            job.setProcessedPatients(processed);
            job.setTotalPatients(total);
            jobRepository.save(job);
        }));
    }

    private boolean isCancelled(UUID jobId) {
        return jobRepository.findById(jobId)
            .map(job -> job.getStatus() == FhirBulkExportJob.Status.CANCELLED)
            .orElse(true);
    }

    private void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ex) {
                    log.warn("Could not delete bulk-export artifact {}: {}", p, ex.toString());
                }
            });
        } catch (IOException ex) {
            log.warn("Could not clean bulk-export dir {}: {}", dir, ex.toString());
        }
    }

    private record PageResult(int patients, boolean hasNext) { }

    /**
     * Lazily-opened per-type NDJSON writers. A file only exists once its
     * first resource is written, so empty types never leave zero-byte
     * files behind and the manifest never lists a file with count 0.
     */
    private static final class NdjsonSink implements Closeable {
        private final Path dir;
        private final IParser parser;
        private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();
        private final Map<String, Integer> counts = new LinkedHashMap<>();

        private NdjsonSink(Path dir, IParser parser) {
            this.dir = dir;
            this.parser = parser.setPrettyPrint(false);
        }

        void write(String type, IBaseResource resource) {
            if (resource == null) return;
            try {
                BufferedWriter writer = writers.computeIfAbsent(type, this::open);
                writer.write(parser.encodeResourceToString(resource));
                writer.write('\n');
                counts.merge(type, 1, Integer::sum);
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to write " + type + " NDJSON line", ex);
            }
        }

        private BufferedWriter open(String type) {
            try {
                return Files.newBufferedWriter(
                    dir.resolve(type + ".ndjson"), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to open " + type + ".ndjson", ex);
            }
        }

        Map<String, Integer> counts() {
            return counts;
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (BufferedWriter writer : writers.values()) {
                try {
                    writer.close();
                } catch (IOException ex) {
                    if (first == null) first = ex;
                }
            }
            writers.clear();
            if (first != null) throw first;
        }
    }
}
