package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D740/D741: every class that reads stored lab results is listed here, as
 * either a read path a reader outside the laboratory reaches — which must go
 * through {@code ClinicalLabResultView}, so an unreleased value or a
 * superseded result never reaches the chart, the PDF, the portal, FHIR or an
 * export — or a laboratory/workflow path, with the reason it may see every row.
 *
 * <p>A new class that injects {@code LabResultRepository} or runs a
 * {@code FROM LabResult} query fails this test
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
        "com/example/hms/service/LabResultServiceImpl.java",                 // list, by-id, compare, trend
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
        Map.entry("com/example/hms/service/signature/SignableReportLocator.java", "owning hospital of a signature"),
        Map.entry("com/example/hms/service/signature/SignableReportFinder.java", "the signer's own worklist: ids, no values"),
        Map.entry("com/example/hms/fhir/FhirTenantBoundary.java", "tenant check on ids, no values")
    );

    /**
     * Methods of a clinical read path that read lab results without the
     * clinical view, keyed {@code path#method}, with the reason. A method that
     * reads {@code labResultRepository} or runs a {@code FROM LabResult} query
     * fails the guard unless it goes through {@code ClinicalLabResultView}, a
     * listed delegate, or is listed here. This list may only shrink.
     */
    private static final Map<String, String> WORKFLOW_METHODS = Map.ofEntries(
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#findRecordedMessage", "HL7 replay lookup"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#completeOrderIfAllReleased", "order completion"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#getAllLabResults", "no endpoint; laboratory list"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#getPendingRelease", "the laboratory's release worklist"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#updateLabResult", "laboratory write"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#deleteLabResult", "laboratory write"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#requireNoLiveResult", "write guard"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#requireSupersedable", "write guard"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#isSupersededByLiveResult", "write guard"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#clearSupersededBackLink", "laboratory write"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#acknowledgeLabResult", "acknowledgement write"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#recordCriticalReadBack", "critical read-back write"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#doReleaseLabResult", "release write"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#signLabResult", "signature write"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#getLabResultsByLabOrderId", "no endpoint; laboratory list"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#getCriticalResults", "critical-value callback, chased before release"),
        Map.entry("com/example/hms/service/LabResultServiceImpl.java#getCriticalResultsRequiringAcknowledgment", "critical-value callback, chased before release"),
        Map.entry("com/example/hms/service/impl/PatientLabResultServiceImpl.java#fetchRows", "rows handed to the clinical view by its caller"),
        Map.entry("com/example/hms/service/impl/PatientLabResultServiceImpl.java#withSiblingsOfSupersedableRows", "sibling rows for the clinical view")
    );

    /** Methods that apply the clinical view for their callers. */
    private static final Set<String> CLINICAL_DELEGATES = Set.of("currentForReader", "isCurrentForReader");

    private static final Pattern METHOD = Pattern.compile(
        "^    (?:(?:public|private|protected|static|final|synchronized)\\s+)+[\\w<>\\[\\],.? ]+?\\s(\\w+)\\s*\\(",
        Pattern.MULTILINE);
    private static final Pattern REPO_READ = Pattern.compile(
        "labResultRepository\\s*\\.\\s*(?!save|delete|flush)\\w+|labResultRepository::(?!save|delete)\\w+");
    private static final Pattern JPQL = Pattern.compile("FROM\\s+LabResult\\b");

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
    void everyReadingMethodOfAClinicalPathGoesThroughTheClinicalView() throws IOException {
        Set<String> missing = new TreeSet<>();
        for (String path : CLINICAL_READ_PATHS) {
            String source = Files.readString(MAIN.resolve(path), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> method : methods(source).entrySet()) {
                String body = method.getValue();
                boolean reads = REPO_READ.matcher(body).find() || JPQL.matcher(body).find();
                if (!reads || WORKFLOW_METHODS.containsKey(path + "#" + method.getKey())) {
                    continue;
                }
                boolean viewed = body.contains("ClinicalLabResultView.")
                    || CLINICAL_DELEGATES.stream().anyMatch(d -> body.contains(d + "("));
                if (!viewed) {
                    missing.add(path + "#" + method.getKey());
                }
            }
        }
        assertThat(missing).as("lab-result reads on a clinical path without ClinicalLabResultView").isEmpty();
    }

    @Test
    void noReaderJudgesARowByItsOwnBackLinkAlone() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (String path : readers()) {
            String source = Files.readString(MAIN.resolve(path), StandardCharsets.UTF_8);
            if (source.contains("ClinicalLabResultView::isReleasedAndCurrent")) {
                offenders.add(path);
            }
        }
        assertThat(offenders).as("judge a row against its order's rows (QA D741)").isEmpty();
    }

    @Test
    void listedWorkflowMethodsStillExist() throws IOException {
        Set<String> gone = new TreeSet<>();
        for (String key : WORKFLOW_METHODS.keySet()) {
            String[] parts = key.split("#");
            String source = Files.readString(MAIN.resolve(parts[0]), StandardCharsets.UTF_8);
            if (!methods(source).containsKey(parts[1])) {
                gone.add(key);
            }
        }
        assertThat(gone).as("stale workflow methods: remove them").isEmpty();
    }

    /** Method name to its source up to the next method declaration; overloads are joined. */
    private static Map<String, String> methods(String source) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = METHOD.matcher(source);
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            names.add(m.group(1));
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : source.length();
            out.merge(names.get(i), source.substring(starts.get(i), end), String::concat);
        }
        return out;
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
                if (source.contains("LabResultRepository") || JPQL.matcher(source).find()) {
                    out.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return out;
    }
}
