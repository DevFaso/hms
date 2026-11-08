package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1538 (round 40): a prenatal ultrasound order and an antenatal OB/GYN referral were accepted for
 * a male patient and for a 6-year-old, while the labour episode refused both: each obstetric entry
 * point checked what it had thought of. Every class that writes an obstetric record is listed here,
 * with the entry point that creates it, and that entry point calls the maternity rule
 * ({@code ObstetricEligibility.requireObstetricEligible}). A writer that only changes a record created
 * through a listed entry point says so.
 */
@DisplayName("D1538: every obstetric entry point calls the obstetric eligibility rule")
class ObstetricEntryPointGuardTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/hms");

    /** The repositories of obstetric records: a class saving to one writes an obstetric record. */
    private static final Set<String> OBSTETRIC_REPOSITORIES = Set.of(
        "UltrasoundOrderRepository", "UltrasoundReportRepository", "ObgynReferralRepository",
        "ObgynReferralMessageRepository", "LaborEpisodeRepository", "LaborPartographEntryRepository",
        "DeliveryRecordRepository", "BirthPlanRepository", "HighRiskPregnancyCarePlanRepository",
        "MaternalHistoryRepository", "PostpartumObservationRepository", "PostpartumCarePlanRepository",
        "NewbornAssessmentRepository");

    private static final String RULE = "ObstetricEligibility.requireObstetricEligible(";

    /** File, and the entry points in it that create an obstetric record: each calls the rule. */
    private static final Map<String, List<String>> ENTRY_POINTS = Map.ofEntries(
        Map.entry("service/impl/UltrasoundServiceImpl.java", List.of("createOrder")),
        Map.entry("service/impl/ObgynReferralServiceImpl.java", List.of("createReferral")),
        Map.entry("service/impl/LaborServiceImpl.java", List.of("startEpisode")),
        Map.entry("service/impl/PostpartumCareServiceImpl.java", List.of("recordObservation")),
        Map.entry("service/BirthPlanServiceImpl.java", List.of("createBirthPlan")),
        Map.entry("service/HighRiskPregnancyCarePlanServiceImpl.java", List.of("createPlan")),
        Map.entry("service/impl/MaternalHistoryServiceImpl.java", List.of("createMaternalHistory", "updateMaternalHistory")),
        Map.entry("service/impl/NewbornAssessmentServiceImpl.java", List.of("recordAssessment")),
        Map.entry("service/PrenatalSchedulingServiceImpl.java", List.of("generateSchedule")),
        Map.entry("service/registry/ProgramEnrollmentService.java", List.of("enroll")));

    /** Writers of obstetric rows that create none: why the rule is not theirs to call. */
    private static final Map<String, String> CHANGES_EXISTING_ONLY = Map.of(
        "service/pro/ProResponseService.java",
        "flags the mental-health referral on a postpartum plan created at delivery (LaborServiceImpl)");

    /** The ultrasound order's delegate: it holds the rule, and skips it only for an emergency scan. */
    private static final String ULTRASOUND_DELEGATE = "requireObstetricPatient(";

    @Test
    @DisplayName("each listed entry point calls the rule")
    void everyEntryPointCallsTheRule() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : ENTRY_POINTS.entrySet()) {
            String code = read(entry.getKey());
            for (String method : entry.getValue()) {
                String body = methodBody(code, method);
                if (body == null) {
                    problems.add(entry.getKey() + "#" + method + " is gone: update the list");
                } else if (!body.contains(RULE) && !body.contains(ULTRASOUND_DELEGATE)) {
                    problems.add(entry.getKey() + "#" + method + " creates an obstetric record without " + RULE);
                }
            }
        }
        String ultrasound = methodBody(read("service/impl/UltrasoundServiceImpl.java"), "requireObstetricPatient");
        assertThat(ultrasound).as("the ultrasound delegate holds the rule").isNotNull().contains(RULE);
        String enroll = methodBody(read("service/registry/ProgramEnrollmentService.java"), "enroll");
        assertThat(enroll).as("the antenatal programme is the one judged").contains("CareProgram.ANC");
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("a class that saves an obstetric record is listed")
    void everyWriterIsListed() throws IOException {
        TreeSet<String> writers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = ROOT.relativize(file).toString().replace('\\', '/');
                if (rel.startsWith("repository/")) {
                    continue;
                }
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (savesObstetricRow(code)) {
                    writers.add(rel);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (String writer : writers) {
            if (!ENTRY_POINTS.containsKey(writer) && !CHANGES_EXISTING_ONLY.containsKey(writer)) {
                problems.add(writer + " saves an obstetric record and is not listed: which entry point calls "
                    + RULE + "?");
            }
        }
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
        assertThat(writers).as("a listed writer that no longer writes comes off the list")
            .containsAll(CHANGES_EXISTING_ONLY.keySet());
    }

    @Test
    @DisplayName("the maternity ward keeps the same rule")
    void theMaternityWardKeepsTheRule() throws IOException {
        String beds = read("service/BedAssignmentService.java");
        assertThat(beds).contains("case MATERNITY -> com.example.hms.utility.ObstetricEligibility.isRecordedMale(patient)")
            .contains("ObstetricEligibility.isRecordedChild(patient)");
    }

    private static boolean savesObstetricRow(String code) {
        for (String repository : OBSTETRIC_REPOSITORIES) {
            Matcher field = Pattern.compile("\\b" + repository + "\\s++(\\w++)\\s*+[;=,)]").matcher(code);
            while (field.find()) {
                if (Pattern.compile("\\b" + field.group(1) + "\\s*+\\.\\s*+save(?:AndFlush|All)?\\(").matcher(code).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String read(String rel) throws IOException {
        return SourceScan.stripComments(Files.readString(ROOT.resolve(rel), StandardCharsets.UTF_8));
    }

    /** The body of the first method declared with this name, or null. */
    private static String methodBody(String code, String name) {
        Matcher m = Pattern.compile("[\\w>\\]]\\s++" + name + "\\s*+\\(").matcher(code);
        while (m.find()) {
            int open = code.indexOf('(', m.start());
            int close = SourceScan.closingParen(code, open);
            int brace = SourceScan.indexOfOutsideLiterals(code, '{', close);
            int semicolon = SourceScan.indexOfOutsideLiterals(code, ';', close);
            if (brace < 0 || (semicolon >= 0 && semicolon < brace)) {
                continue;
            }
            return code.substring(brace, SourceScan.closingBrace(code, brace) + 1);
        }
        return null;
    }
}
