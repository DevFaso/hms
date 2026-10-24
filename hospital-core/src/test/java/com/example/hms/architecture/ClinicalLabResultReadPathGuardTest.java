package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D740/D741: every class that reads stored lab results is listed here, as
 * either a read path a reader outside the laboratory reaches — which must go
 * through {@code ClinicalLabResultView}, so an unreleased value or a
 * superseded result never reaches the chart, the PDF, the portal, FHIR or an
 * export — or a laboratory/workflow path, with the reason it may see every row.
 *
 * <p>A new class that injects {@code LabResultRepository} fails this test
 * until it is placed in one list. The workflow list may only shrink.
 */
class ClinicalLabResultReadPathGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** Read paths that serve results outside the laboratory. */
    private static final Set<String> CLINICAL_READ_PATHS = Set.of(
        "com/example/hms/service/impl/ChartReviewServiceImpl.java",          // chart + record.pdf
        "com/example/hms/service/PatientServiceImpl.java",                   // timeline + patient record
        "com/example/hms/service/PatientSnapshotServiceImpl.java",           // staff snapshot
        "com/example/hms/service/impl/PatientLabResultServiceImpl.java",     // portal + staff lab list
        "com/example/hms/service/ResultReviewServiceImpl.java",              // doctor's review queue
        "com/example/hms/service/LabResultServiceImpl.java",                 // trend
        "com/example/hms/fhir/provider/ObservationFhirResourceProvider.java",
        "com/example/hms/fhir/provider/DiagnosticReportFhirResourceProvider.java",
        "com/example/hms/fhir/bulk/FhirBulkExportRunner.java",
        "com/example/hms/fhir/everything/PatientEverythingService.java"
    );

    /** Paths that see every row, with the reason. */
    private static final Map<String, String> WORKFLOW_PATHS = Map.ofEntries(
        Map.entry("com/example/hms/repository/LabResultRepository.java", "the repository itself"),
        Map.entry("com/example/hms/repository/ImagingReportRepository.java", "javadoc reference only"),
        Map.entry("com/example/hms/bootstrap/DevSyntheticDataSeeder.java", "dev seeding, writes only"),
        Map.entry("com/example/hms/fhir/write/ObservationFhirWriteService.java", "write path"),
        Map.entry("com/example/hms/service/CriticalValueNotificationService.java", "critical escalation of the row itself"),
        Map.entry("com/example/hms/service/DoctorWorklistServiceImpl.java", "a count, no values"),
        Map.entry("com/example/hms/service/InstrumentOutboxServiceImpl.java", "instrument transmit"),
        Map.entry("com/example/hms/service/LabOrderServiceImpl.java", "existence check for a performer change"),
        Map.entry("com/example/hms/service/SuperAdminDashboardServiceImpl.java", "a count, no values"),
        Map.entry("com/example/hms/service/integration/impl/MllpInboundLabServiceImpl.java", "HL7 ingest"),
        Map.entry("com/example/hms/service/signature/SignableReportLocator.java", "owning hospital of a signature")
    );

    @Test
    void everyLabResultReaderIsClassified() throws IOException {
        Set<String> unclassified = new TreeSet<>();
        for (String path : readers()) {
            if (!CLINICAL_READ_PATHS.contains(path) && !WORKFLOW_PATHS.containsKey(path)) {
                unclassified.add(path);
            }
        }
        assertThat(unclassified)
            .as("classify each new LabResultRepository user in ClinicalLabResultReadPathGuardTest")
            .isEmpty();
    }

    @Test
    void everyClinicalReadPathGoesThroughTheClinicalView() throws IOException {
        Set<String> missing = new TreeSet<>();
        for (String path : CLINICAL_READ_PATHS) {
            String source = Files.readString(MAIN.resolve(path), StandardCharsets.UTF_8);
            if (!source.contains("ClinicalLabResultView.")) {
                missing.add(path);
            }
        }
        assertThat(missing).as("clinical lab-result read paths without ClinicalLabResultView").isEmpty();
    }

    @Test
    void listedPathsStillExist() {
        Set<String> gone = new TreeSet<>();
        Stream.concat(CLINICAL_READ_PATHS.stream(), WORKFLOW_PATHS.keySet().stream())
            .filter(path -> !Files.exists(MAIN.resolve(path)))
            .forEach(gone::add);
        assertThat(gone).as("stale entries: remove them").isEmpty();
    }

    private static Set<String> readers() throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("LabResultRepository")) {
                    out.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return out;
    }
}
