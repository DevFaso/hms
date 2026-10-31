package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Round 19 (fix/r19-lab-results-chart) class guards, one test per class. Each
 * scans every production source, so a new path of the class fails here, not
 * at the next QA round.
 */
class LabResultChartRound19GuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static Map<String, String> sources() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                out.put(MAIN.relativize(f).toString().replace('\\', '/'),
                    SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    // ── QA D1095: a lab value is never written out without its unit ─────────

    /**
     * The stored unit is blank when the technician left it blank; the unit the
     * value is in is then the test definition's. Only {@code LabResultMapper.unitOf}
     * (and the write path that stores what was typed) may read the stored
     * column; everything that SHOWS or SENDS a value reads unitOf, so a blank
     * unit never reaches a notification, a chronology or a PDF as "0,40".
     */
    private static final Map<String, String> RAW_UNIT_READERS = Map.of(
        "mapper/LabResultMapper.java", "the one reader: unitOf / unitOrTestUnit",
        "service/LabResultServiceImpl.java", "the write path: stores and compares the unit as entered (defaulted by unitOrTestUnit)",
        "service/impl/ChartReviewServiceImpl.java", "reads ResultEntryDTO.getResultUnit(), which was built from unitOf",
        "service/PatientRecordPdfService.java", "reads ResultEntryDTO.getResultUnit(), which was built from unitOf");

    @Test
    void d1095_theStoredUnitIsReadOnlyThroughUnitOf() throws IOException {
        List<String> offenders = new ArrayList<>();
        sources().forEach((rel, src) -> {
            if (src.contains(".getResultUnit()") && !RAW_UNIT_READERS.containsKey(rel)) {
                offenders.add(rel);
            }
        });
        assertThat(offenders)
            .as("QA D1095: read a lab result's unit through LabResultMapper.unitOf (the test definition's "
                + "unit when none was typed); " + SourceScan.MANDATE)
            .isEmpty();
    }

    /** Every class that reads a result's value also reads its unit (unitOf, withUnit, or the DTO's resultUnit). */
    private static final Map<String, String> VALUE_WITHOUT_UNIT = Map.of();

    @Test
    void d1095_everyClassThatReadsAValueReadsItsUnit() throws IOException {
        Pattern unit = Pattern.compile("unitOf\\(|withUnit\\(|getResultUnit\\(\\)|\\.resultUnit\\(");
        List<String> offenders = new ArrayList<>();
        sources().forEach((rel, src) -> {
            if (src.contains(".getResultValue()") && !unit.matcher(src).find()
                    && !VALUE_WITHOUT_UNIT.containsKey(rel)) {
                offenders.add(rel);
            }
        });
        assertThat(offenders)
            .as("QA D1095: a class that reads a lab value writes it out with its unit; " + SourceScan.MANDATE)
            .isEmpty();
    }

    // ── QA D1092: no path releases a result without asking whether it was graded ─

    @Test
    void d1092_everyReleasePathAsksWhetherTheResultCouldBeGraded() throws IOException {
        List<String> offenders = new ArrayList<>();
        sources().forEach((rel, src) -> {
            if (src.contains("setReleased(true)") && !src.contains("isUngradedForUnitMismatch(")) {
                offenders.add(rel);
            }
        });
        assertThat(offenders)
            .as("QA D1092: a path that releases a lab result must ask LabResultMapper.isUngradedForUnitMismatch "
                + "(not auto-released, a confirmed manual release, an in-basket item flagged for review); "
                + SourceScan.MANDATE)
            .isEmpty();
    }

    // ── QA D1094: a visit is not its lead clinician's alone ────────────────

    /** An entity invariant that ties an order to the visit's clinician of record (a 500 for a colleague). */
    private static final Pattern VISIT_LEAD_INVARIANT =
        Pattern.compile("encounter\\.getStaff\\(\\)\\.getId\\(\\)");

    private static final Map<String, String> VISIT_LEAD_ALLOWED = Map.of(
        "model/Prescription.java",
        "owned by fix/r19-orders-encounters-clinical-ui; the prescription service refuses first with a "
            + "translated 400 (prescription.encounter.staff.mismatch), flagged in the hand-back");

    @Test
    void d1094_noEntityTiesAnOrderToTheVisitsLeadClinician() throws IOException {
        List<String> offenders = new ArrayList<>();
        sources().forEach((rel, src) -> {
            if (rel.startsWith("model/") && VISIT_LEAD_INVARIANT.matcher(src).find()
                    && !VISIT_LEAD_ALLOWED.containsKey(rel)) {
                offenders.add(rel);
            }
        });
        assertThat(offenders)
            .as("QA D1094: any treating clinician of the hospital orders on an open visit; an entity "
                + "invariant on encounter.staff answers a colleague with a 500; " + SourceScan.MANDATE)
            .isEmpty();
    }

    // ── QA D1109: a specimen write consults the order's lifecycle ──────────

    @Test
    void d1109_everySpecimenWriteAsksTheOrdersLifecycle() throws IOException {
        String src = sources().get("service/LabSpecimenServiceImpl.java");
        assertThat(src).isNotNull();
        Map<String, String> methodRule = Map.of(
            "createSpecimen", "acceptsNewSpecimen",
            "receiveSpecimen", "acceptsSpecimenReceipt",
            "rejectSpecimen", "acceptsSpecimenRejection");
        List<String> offenders = new ArrayList<>();
        methodRule.forEach((method, rule) -> {
            String body = methodBody(src, method);
            if (body == null || !body.contains(rule + "(")) {
                offenders.add(method + " must call LabOrderLifecycle." + rule);
            }
        });
        // And no other method of the class saves a specimen.
        Matcher save = Pattern.compile("labSpecimenRepository\\.save\\(").matcher(src);
        int saves = 0;
        while (save.find()) {
            saves++;
        }
        assertThat(saves).as("a new specimen write path must be added to this guard").isEqualTo(3);
        assertThat(offenders).as("QA D1109: " + SourceScan.MANDATE).isEmpty();
    }

    // ── QA D1120: a definition's code is live once per catalogue ───────────

    @Test
    void d1120_everyDefinitionWritePathChecksTheCode() throws IOException {
        Map<String, String> all = sources();
        String service = all.get("service/LabTestDefinitionServiceImpl.java");
        assertThat(methodBody(service, "create")).contains("requireCodeFree(");
        assertThat(methodBody(service, "update")).contains("requireCodeFree(");
        List<String> builders = new ArrayList<>();
        all.forEach((rel, src) -> {
            if (src.contains("LabTestDefinition.builder()") && !rel.equals("service/LabTestDefinitionServiceImpl.java")
                    && !rel.equals("mapper/LabTestDefinitionMapper.java")
                    && !rel.equals("bootstrap/DevSyntheticDataSeeder.java")) {
                builders.add(rel);
            }
        });
        assertThat(builders)
            .as("QA D1120: a new place that builds a lab test definition must go through "
                + "LabTestDefinitionServiceImpl.create (the code check) or be added here with its reason; "
                + "the mapper is called only by create, the dev seeder looks a code up before building")
            .isEmpty();
    }

    // ── QA D1113: a transcribed dose is not attributed to the recording hospital ─

    @Test
    void d1113_everyImmunizationReaderThatAttributesAHospitalKnowsTheSource() throws IOException {
        List<String> offenders = new ArrayList<>();
        sources().forEach((rel, src) -> {
            boolean readsDoses = src.contains("PatientImmunization dose") || src.contains("PatientImmunization src");
            if (readsDoses && (src.contains("provenance(") || src.contains("setPrimarySource"))
                    && !src.contains("transcribedSourceOf(")) {
                offenders.add(rel);
            }
        });
        assertThat(offenders).as("QA D1113: " + SourceScan.MANDATE).isEmpty();
        assertThat(sources().get("fhir/mapper/ImmunizationFhirMapper.java")).contains("transcribedSourceOf(");
        assertThat(sources().get("service/timeline/VisitTimelineRows.java")).contains("transcribedSourceOf(");
    }

    /** The body of the first method named {@code name} declared in {@code src}, or null. */
    static String methodBody(String src, String name) {
        Matcher m = Pattern.compile("\\n\\s+(?:public|private|protected|static|\\s)*[\\w<>\\[\\], .?]+\\s+"
            + Pattern.quote(name) + "\\s*\\(").matcher(src);
        if (!m.find()) {
            return null;
        }
        int open = src.indexOf('{', m.end());
        int close = open < 0 ? -1 : SourceScan.closingBrace(src, open);
        return close < 0 ? null : src.substring(open, close + 1);
    }

    @Test
    void theScansSeeTheShapesTheDefectsHad() {
        String src = """
            class X {
                public Dto createSpecimen(Request r) {
                    labSpecimenRepository.save(s);
                }
            }
            """;
        assertThat(methodBody(src, "createSpecimen")).doesNotContain("acceptsNewSpecimen(");
        assertThat(VISIT_LEAD_INVARIANT.matcher(
            "if (!Objects.equals(encounter.getStaff().getId(), orderingStaff.getId()))").find()).isTrue();
    }
}
