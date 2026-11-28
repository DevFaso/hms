package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4140, D4142 (r81): no step of an imaging exam is taken on a request no physician stands behind,
 * and no report is written on an exam that never started.
 *
 * <p>The class this guards: a write path that places an imaging order, or moves its exam forward,
 * without asking {@code ImagingOrderAuthorisation}. A nurse's request was « prescrit » at once and
 * was checked in, started and reported with no physician. Three checks, over the whole main tree:
 * <ul>
 *   <li>whoever builds a new order stamps who asked ({@code stampRequest}), which is what keeps a
 *       nurse's or midwife's request pending;</li>
 *   <li>whoever takes a step of the exam (a status write, the check-in, the exam record, a report, its
 *       signature, an image) asks the rule first, in that method;</li>
 *   <li>a new class that touches one of these markers fails here until it is listed with its check.</li>
 * </ul>
 */
@DisplayName("QA D4140/D4142: every imaging exam step asks whether a physician stands behind the order")
class ImagingOrderAuthorisationGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** What makes a file a place where an order is created or its exam moved forward. */
    private static final Pattern STEP_MARKER = Pattern.compile(
        "imagingOrderMapper\\.toEntity\\(|ImagingOrder\\.builder\\(\\)|new ImagingOrder\\(\\)"
            + "|\\.setCheckedInAt\\(|ImagingExamRecord\\.stampPerformer\\(|\\.setExamRecordedAt\\("
            + "|new ImagingReport\\(\\)|ImagingStudyImage\\.builder\\(\\)|\\.setSignedAt\\(");

    /** Each such file, and for each of its methods that takes a step, the call it must make. */
    private static final Map<String, Map<String, String>> STEPS = Map.of(
        "service/impl/ImagingOrderServiceImpl.java", Map.of(
            "public ImagingOrderResponseDTO createOrder(", "authorisation().stampRequest(",
            "public ImagingOrderResponseDTO updateOrder(", "authorisation().awaitsPhysician(order)",
            "public ImagingOrderResponseDTO updateOrderStatus(", "authorisation().requireAuthorisedFor(order, request.getStatus())",
            "public ImagingOrderResponseDTO checkInPatient(", "authorisation().requireAuthorised(order)",
            "public ImagingOrderResponseDTO recordExam(", "authorisation().requireAuthorised(order)"),
        "service/impl/ImagingReportServiceImpl.java", Map.of(
            "public ImagingReportResponseDTO createReport(", ".requireExamTookPlace(imagingOrder)",
            "public ImagingReportResponseDTO updateReport(", ".requireExamTookPlace(imagingOrder)",
            "public ImagingReportResponseDTO signReport(", ".requireExamTookPlace(report.getImagingOrder())"),
        "service/imaging/ImagingStudyImageService.java", Map.of(
            "public ImagingStudyImageDTO upload(", ".requireExamTookPlace(order)"));

    /** Files the markers find that take no step of an exam, with the reason. */
    private static final Map<String, String> NOT_A_STEP = Map.of(
        "mapper/ImagingOrderMapper.java",
        "builds the entity from the request for ImagingOrderServiceImpl.createOrder, which stamps it",
        "mapper/ImagingReportMapper.java",
        "copies a request onto a report ImagingReportServiceImpl.createReport or updateReport has already checked");

    @Test
    void everyClassThatPlacesAnOrderOrMovesAnExamIsListed() throws IOException {
        List<String> unlisted = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = MAIN.relativize(file).toString().replace('\\', '/');
                String source = Files.readString(file, StandardCharsets.UTF_8);
                boolean imaging = source.contains("ImagingOrder") || source.contains("ImagingReport")
                    || source.contains("ImagingStudyImage");
                if (imaging && STEP_MARKER.matcher(source).find() && !STEPS.containsKey(name)
                        && !NOT_A_STEP.containsKey(name)) {
                    unlisted.add(name);
                }
            }
        }
        assertThat(unlisted)
            .as("places an imaging order or moves its exam forward without a listed ImagingOrderAuthorisation check")
            .isEmpty();
    }

    @Test
    void everyStepAsksTheRuleInItsOwnMethod() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> file : STEPS.entrySet()) {
            String source = Files.readString(MAIN.resolve(file.getKey()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            for (Map.Entry<String, String> step : file.getValue().entrySet()) {
                int start = source.indexOf(step.getKey());
                if (start < 0) {
                    missing.add(file.getKey() + ": no method " + step.getKey());
                    continue;
                }
                int end = source.indexOf("\n    }\n", start);
                String body = source.substring(start, end < 0 ? source.length() : end);
                if (!body.contains(step.getValue())) {
                    missing.add(file.getKey() + " " + step.getKey() + "…) does not call " + step.getValue());
                }
            }
        }
        assertThat(missing).as("an imaging exam step that no longer asks the rule").isEmpty();
    }

    @Test
    void theListedExceptionsStillExist() {
        assertThat(NOT_A_STEP.keySet()).allSatisfy(name -> assertThat(MAIN.resolve(name)).exists());
    }
}
