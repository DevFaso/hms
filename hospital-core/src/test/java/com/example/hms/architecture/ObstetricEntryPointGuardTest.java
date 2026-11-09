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
        Map.entry("service/impl/UltrasoundServiceImpl.java", List.of("judgeOnCreate", "requireObstetricPatientOnEdit")),
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

    /**
     * QA D1538: the emergency each order or referral is judged with, as the shared rule's third argument
     * (the class comment of ObstetricEligibility): an ultrasound order's URGENT or STAT, a referral's URGENT.
     */
    private static final Map<String, String> EMERGENCY_ARGUMENT = Map.of(
        "service/impl/UltrasoundServiceImpl.java#judgeOnCreate", "ClinicalPriority.isEmergency(request.getPriority())",
        "service/impl/ObgynReferralServiceImpl.java#createReferral", "request.getUrgency() == com.example.hms.enums.ObgynReferralUrgency.URGENT");

    @Test
    @DisplayName("each listed entry point calls the rule")
    void everyEntryPointCallsTheRule() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : ENTRY_POINTS.entrySet()) {
            String code = read(entry.getKey());
            for (String method : entry.getValue()) {
                String body = SourceScan.methodBody(code, method);
                if (body == null) {
                    problems.add(entry.getKey() + "#" + method + " is gone: update the list");
                } else if (!body.contains(RULE)) {
                    problems.add(entry.getKey() + "#" + method + " creates an obstetric record without " + RULE);
                } else if (EMERGENCY_ARGUMENT.containsKey(entry.getKey() + "#" + method)
                    && !body.replaceAll("\\s++", " ").contains(EMERGENCY_ARGUMENT.get(entry.getKey() + "#" + method))) {
                    problems.add(entry.getKey() + "#" + method + " no longer judges its emergency with `"
                        + EMERGENCY_ARGUMENT.get(entry.getKey() + "#" + method) + "`");
                }
            }
        }
        String ultrasound = read("service/impl/UltrasoundServiceImpl.java");
        String update = SourceScan.methodBody(ultrasound, "updateOrder");
        assertThat(update).as("an edit that leaves an exemption is judged again").contains("requireObstetricPatientOnEdit(");
        // QA D1538 review: an emergency placed for a patient the rule would refuse is flagged and audited,
        // never silent: each creating path keeps what the rule returned and records it.
        String create = SourceScan.methodBody(ultrasound, "createOrder");
        assertThat(create).contains("judgeOnCreate(").contains("setObstetricRuleSkipped(ruleSkipped)").contains("skipRecorder.record(");
        assertThat(update).contains("setObstetricRuleSkipped(flagged)").contains("skipRecorder.record(")
            .as("a flag whose reason is gone is cleared, and the clearing audited").contains("skipRecorder.recordCleared(");
        String referral = SourceScan.methodBody(read("service/impl/ObgynReferralServiceImpl.java"), "createReferral");
        assertThat(referral).contains("boolean ruleSkipped = ").contains(".obstetricRuleSkipped(ruleSkipped)")
            .contains("skipRecorder.record(");
        String enroll = SourceScan.methodBody(read("service/registry/ProgramEnrollmentService.java"), "enroll");
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
}
