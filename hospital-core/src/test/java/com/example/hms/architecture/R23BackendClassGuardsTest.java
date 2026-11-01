package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codebase-wide guards for the round-23 backend classes whose rule is a
 * shape in the source: QA D1204, D1212, D1213, D1214, D1215 and D1221. Each
 * scans the whole main tree (or names the one method the class lives in and
 * fails if it moves), so the class cannot come back on a new screen.
 */
class R23BackendClassGuardsTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static String read(String rel) throws IOException {
        return SourceScan.stripComments(Files.readString(MAIN.resolve(rel), StandardCharsets.UTF_8));
    }

    private static String methodBody(String src, String signature) {
        int at = src.indexOf(signature);
        assertThat(at).as("the method %s moved: update the guard", signature).isNotNegative();
        int open = src.indexOf('{', at);
        return src.substring(open, SourceScan.closingBrace(src, open) + 1);
    }

    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> out = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                out.put(MAIN.relativize(file).toString().replace('\\', '/'),
                    SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    /**
     * QA D1204: one key answered both "no specimen collected yet" and "the
     * specimen was rejected". Every file that refuses with the first must
     * also know the second.
     */
    @Test
    void d1204_theNoSpecimenRefusalAlwaysKnowsARejection() throws IOException {
        Set<String> blind = new TreeSet<>();
        mainSources().forEach((rel, src) -> {
            if (src.contains("\"labresult.specimen.required\"") && !src.contains("\"labresult.specimen.rejected\"")) {
                blind.add(rel);
            }
        });
        assertThat(blind).as("a 'no specimen' refusal that cannot tell a rejected specimen (QA D1204)%s",
            SourceScan.MANDATE).isEmpty();
    }

    /**
     * QA D1212: a read authorised by the patient's own grant (proxy access)
     * looked the grantor up under the PROXY's hospital tenancy and answered
     * 404 for a grantor registered elsewhere. The grant check reads the
     * grantor unscoped, after the grant is verified.
     */
    @Test
    void d1212_theProxyGrantReadsTheGrantorWithoutTheProxysTenancy() throws IOException {
        String body = methodBody(read("service/impl/PatientPortalServiceImpl.java"),
            "private Patient verifyProxyAccess(");
        assertThat(body).contains("patientRepository.findByIdUnscoped(")
            .doesNotContain("patientRepository.findById(");
        assertThat(body.indexOf("findByIdUnscoped(")).as("the grant is checked before the unscoped read")
            .isGreaterThan(body.indexOf("findByGrantorPatient_IdAndProxyUser_IdAndStatus("));
    }

    /**
     * QA D1213: the portal page was cut at the limit BEFORE the superseded
     * rows were removed. The pairing step takes no limit any more, and every
     * limit of the patient lab-result reads is applied to the filtered list.
     */
    @Test
    void d1213_theLabResultPairingNeverCutsBeforeTheFilter() throws IOException {
        String src = read("service/impl/PatientLabResultServiceImpl.java");
        String pairing = methodBody(src, "private List<LabResult> resolvePairs(");
        assertThat(pairing).doesNotContain(".limit(");
        assertThat(src).doesNotContainPattern("resolvePairs\\([^)]*,[^)]*,[^)]*\\)");
        assertThat(src).contains("limited(currentFor(");
    }

    /**
     * QA D1214: the portal health summary never set activeDiagnoses. Every
     * place that builds a HealthSummaryDTO (own summary, proxy records) sets it.
     */
    @Test
    void d1214_everyHealthSummaryCarriesTheActiveDiagnoses() throws IOException {
        Set<String> missing = new TreeSet<>();
        int builders = 0;
        for (Map.Entry<String, String> e : mainSources().entrySet()) {
            String src = e.getValue();
            Matcher m = Pattern.compile("\\bHealthSummaryDTO\\.builder\\(\\)").matcher(src);
            while (m.find()) {
                builders++;
                int end = src.indexOf(".build()", m.end());
                if (end < 0 || !src.substring(m.end(), end).contains(".activeDiagnoses(")) {
                    missing.add(e.getKey() + ":" + SourceScan.lineOf(src, m.start()));
                }
            }
        }
        assertThat(builders).isPositive();
        assertThat(missing).as("a health summary built without activeDiagnoses (QA D1214)%s", SourceScan.MANDATE)
            .isEmpty();
    }

    /**
     * QA D1215: a request date range (from/to, start/end) was accepted
     * reversed. Every request payload with such a pair is listed with the
     * place that refuses a reversed range; a new pair fails until it is.
     */
    private static final Map<String, String> RANGE_CHECKS = Map.of(
        "payload/dto/WaitlistEntryRequestDTO.java",
            "service/ReceptionServiceImpl.java#requireOfferableRange(req.getRequestedDateFrom(), req.getRequestedDateTo()",
        "payload/dto/StaffRequestDTO.java", "service/StaffServiceImpl.java#getEndDate().isBefore(",
        "payload/dto/scheduling/SessionTemplateRequestDTO.java",
            "service/scheduling/SessionTemplateServiceImpl.java#getEffectiveTo().isBefore(",
        "payload/dto/PatientInsuranceRequestDTO.java", "payload/dto/PatientInsuranceRequestDTO.java#isDateRangeValid(",
        "payload/dto/PatientPrimaryCareRequestDTO.java", "payload/dto/PatientPrimaryCareRequestDTO.java#isDateRangeValid(",
        "payload/dto/AdvanceDirectiveRequestDTO.java",
            "service/impl/AdvanceDirectiveServiceImpl.java#getExpirationDate().isBefore(",
        "payload/dto/OnCallScheduleRequestDTO.java",
            "service/impl/OnCallScheduleServiceImpl.java#getEndTime().isAfter(request.getStartTime())");

    private static final Pattern START_FIELD = Pattern.compile(
        "private (?:java\\.time\\.)?(?:LocalDate|LocalDateTime|OffsetDateTime|Instant) "
            + "\\w*(?:From|Start|start[A-Z]\\w*|effective[A-Z]\\w*)\\s*;");
    private static final Pattern END_FIELD = Pattern.compile(
        "private (?:java\\.time\\.)?(?:LocalDate|LocalDateTime|OffsetDateTime|Instant) "
            + "\\w*(?:To|End|end[A-Z]\\w*|expiration[A-Z]\\w*)\\s*;");

    @Test
    void d1215_everyRequestDateRangeRefusesAReversedRange() throws IOException {
        Map<String, String> sources = mainSources();
        Set<String> unlisted = new TreeSet<>();
        Set<String> unchecked = new TreeSet<>();
        sources.forEach((rel, src) -> {
            if (!rel.startsWith("payload/") || !rel.contains("Request")) {
                return;
            }
            if (START_FIELD.matcher(src).find() && END_FIELD.matcher(src).find()) {
                String check = RANGE_CHECKS.get(rel);
                if (check == null) {
                    unlisted.add(rel);
                } else {
                    String[] where = check.split("#", 2);
                    String checker = sources.get(where[0]);
                    if (checker == null || !checker.contains(where[1])) {
                        unchecked.add(rel + " -> " + check);
                    }
                }
            }
        });
        assertThat(unlisted).as("a request date range with no reversed-range check (QA D1215): add the check and"
            + " list it in RANGE_CHECKS%s", SourceScan.MANDATE).isEmpty();
        assertThat(unchecked).as("a listed range check that is gone").isEmpty();
    }

    /**
     * QA D1221: the identity check of a fill (is this lot the prescribed
     * drug?) runs before the checks that only mean something for the right
     * drug: the form, the whole units and the FEFO order.
     */
    @Test
    void d1221_theWrongDrugCheckRunsBeforeFormUnitsAndFefo() throws IOException {
        String body = methodBody(read("service/pharmacy/DispenseServiceImpl.java"),
            "private FillContext validateFill(");
        int verify = body.indexOf("dispenseVerificationService.verify(");
        assertThat(verify).isPositive();
        assertThat(verify).isLessThan(body.indexOf("DispenseUnitForms.requireCompatible("))
            .isLessThan(body.indexOf("requireWholeUnitsOfFill("))
            .isLessThan(body.indexOf("recordFefoOverride("));
    }
}
