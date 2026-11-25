package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.config.SecurityConstants;
import com.example.hms.testsupport.SourceScan;
import com.example.hms.service.support.PositiveAntibodyScreenPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * QA D3351 (r70), rule TRANSFUSION_POSITIVE_ANTIBODY_SCREEN. The class this guards: a way out of the laboratory
 * that the rule is never asked about. SHOT ranks failed "warning flags or logic rules" first among IT transfusion
 * errors; here the rule lives in one service, and every place a unit leaves, a crossmatch is written or a request
 * is raised must call it.
 *
 * <p>{@code putOut} is "the one place the bag leaves the lab": each method that calls it must have asked the rule
 * first. A new way out fails here until it does.
 */
class PositiveAntibodyScreenGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path SERVICE = MAIN.resolve("service/impl/TransfusionServiceImpl.java");
    private static final Path CONTROLLER = MAIN.resolve("controller/TransfusionController.java");

    private static String code(Path file) throws IOException {
        return SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The body of {@code public … name(} up to the next method at the same indentation. */
    private static String method(String code, String signatureStart) {
        int from = code.indexOf(signatureStart);
        assertThat(from).as(signatureStart).isNotNegative();
        Matcher next = Pattern.compile("\\n    (?:@Override|public |private |protected |/\\*\\*)").matcher(code);
        int to = next.find(from + signatureStart.length()) ? next.start() : code.length();
        return code.substring(from, to);
    }

    @Test
    @DisplayName("every method that puts a unit out asks the rule before it does")
    void everyWayOutAsksTheRule() throws IOException {
        String code = code(SERVICE);
        Matcher putOut = Pattern.compile("return putOut\\(").matcher(code);
        int ways = 0;
        while (putOut.find()) {
            ways++;
        }
        assertThat(ways).as("the ways a unit leaves the laboratory: a new one must ask the rule and be counted here")
            .isEqualTo(2);

        String issue = method(code, "public BloodUnitResponseDTO issueUnit(");
        assertThat(issue).contains("antibodyScreen.requireRoutineIssueAllowed(request, crossmatch)")
            .contains("antibodyScreen.requireEmergencyReleaseAllowed(request)")
            .contains("antibodyScreen.askRetrospectiveCrossmatch(request, unit)");
        assertThat(issue.indexOf("antibodyScreen.requireRoutineIssueAllowed")).isLessThan(issue.indexOf("return putOut("));

        // The documented release asks through two helpers of its own; each is pinned to the rule's call.
        String emergency = method(code, "public BloodUnitResponseDTO emergencyRelease(");
        assertThat(emergency).contains("boolean crossmatchAfterwards = antibodyEmergencyPath(request);")
            .contains("askCrossmatchAfterwards(crossmatchAfterwards, release.getReleaseType(), request, unit);");
        assertThat(emergency.indexOf("antibodyEmergencyPath(request)")).isLessThan(emergency.indexOf("recordRelease("));
        assertThat(emergency.indexOf("askCrossmatchAfterwards(")).isLessThan(emergency.indexOf("return putOut("));
        assertThat(method(code, "private boolean antibodyEmergencyPath("))
            .contains("antibodyScreen.requireEmergencyReleaseAllowed(request)");
        assertThat(method(code, "private void askCrossmatchAfterwards("))
            .contains("antibodyScreen.askRetrospectiveCrossmatch(request, unit)");
    }

    @Test
    @DisplayName("a crossmatch result and a new request go through the rule, and a positive screen asks the biologist")
    void theOtherTransitionsAskTheRule() throws IOException {
        String code = code(SERVICE);
        assertThat(method(code, "public CrossmatchResponseDTO recordCrossmatch("))
            .contains("antibodyScreen.requireCompatibilised(transfusionRequest, request)");
        assertThat(method(code, "public TransfusionRequestResponseDTO createRequest("))
            .contains("antibodyScreen.requireEmergencyDeclaration(request, patient.getId(), urgency)");
        assertThat(method(code, "public PatientBloodGroupResponseDTO recordBloodGroup("))
            .contains("antibodyScreen.askBiologistToIdentify(saved)");
        // The flag reaches the screens that ask for blood: the group read before a request, and the request itself.
        assertThat(method(code, "public java.util.Optional<PatientBloodGroupResponseDTO> findCurrentBloodGroup("))
            .contains("withAntibodyFlag(dto, patientId, hospitalId)");
        assertThat(method(code, "public PatientBloodGroupResponseDTO getCurrentBloodGroup("))
            .contains("findCurrentBloodGroup(patientId)");
        // The request shows the flag, and the injection is required: the rule is never silently absent.
        assertThat(code).contains("detail.setAlloImmunised(true);").containsPattern(
            "@org\\.springframework\\.beans\\.factory\\.annotation\\.Autowired\\s+public void setAntibodyScreen\\(");
    }

    @Test
    @DisplayName("only the biologist's own role records the antibody: no super-admin, no technician, no prescriber")
    void onlyTheBiologistRecordsTheAntibody() throws IOException {
        String controller = code(CONTROLLER);
        int at = controller.indexOf("@PostMapping(\"/antibody-workups\")");
        assertThat(at).isNotNegative();
        String guard = controller.substring(at, controller.indexOf("public ResponseEntity", at));
        assertThat(guard).contains("ClinicalActor).holdsOwnRole(authentication, 'LAB_DIRECTOR')")
            .doesNotContain("SUPER_ADMIN").doesNotContain("hasAnyRole");
        assertThat(PositiveAntibodyScreenPolicy.BIOLOGIST_ROLE_CODES).containsExactly(SecurityConstants.ROLE_LAB_DIRECTOR);
        assertThat(code(MAIN.resolve("service/transfusion/PositiveAntibodyScreenService.java")))
            .contains("assignmentRepository.existsActiveWitness(userId, hospitalId,");
    }

    @Test
    @DisplayName("the rule's service has no way to clear the flag or to delete a record")
    void nothingClearsTheFlag() throws IOException {
        String rule = code(MAIN.resolve("service/transfusion/PositiveAntibodyScreenService.java"));
        assertThat(rule).doesNotContain(".delete(").doesNotContain("deleteBy").doesNotContain("setSuperseded");
        assertThat(code(MAIN.resolve("repository/PatientAntibodyWorkupRepository.java")))
            .doesNotContain("delete").doesNotContain("@Modifying");
    }

    /**
     * Sonar S6809 (r70): the rule's service opens no transaction of its own (its annotations were never applied:
     * the methods call each other). What it reads, writes and refuses happens in the transaction of the request,
     * the crossmatch or the release that asked, which holds only while the transfusion service, transactional as
     * a class, is its one caller. A second caller fails here until its own transaction is decided.
     */
    @Test
    @DisplayName("the rule runs in its caller's transaction: it declares none, and only the transfusion service calls it")
    void theRuleRunsInItsCallersTransaction() throws IOException {
        assertThat(code(MAIN.resolve("service/transfusion/PositiveAntibodyScreenService.java")))
            .doesNotContain("@Transactional");
        assertThat(code(SERVICE)).containsPattern("@Transactional\\s+public class TransfusionServiceImpl ");

        List<String> callers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                // The service itself, and the register of rules, which names it as text.
                if (!name.equals("PositiveAntibodyScreenService.java") && !name.equals("ClinicalRuleRegistry.java")
                        && code(file).contains("PositiveAntibodyScreenService")) {
                    callers.add(name);
                }
            }
        }
        // QA D3901 (r77): the patient record and the safety strip show the flag too, through one read-only call of
        // the accounted status read, made inside the read-only transaction of the chart read that shows it.
        assertThat(callers).containsExactlyInAnyOrder("TransfusionServiceImpl.java",
            "PatientBloodGroupStatusService.java");
        String record = code(MAIN.resolve("service/transfusion/PatientBloodGroupStatusService.java"));
        assertThat(record).contains("screens.status(patient.getId(), hospitalId)")
            .doesNotContainPattern("screens\\.(?!status\\()\\w+\\(");
    }

    /**
     * Owner decision 2026-11-22 (research note « antibody history sharing »): the flag follows the patient across
     * hospitals, whatever the sharing choice, with the minimum of another hospital's record, on the transfusion
     * path only, and each time it is shown the read is accounted (which also puts it in the patient's own
     * « Qui a consulté mon dossier »). The class this guards: the patient-wide reads gaining a second reader (a
     * chart, a summary, an export) that would show another hospital's antibody record with no account of it.
     */
    @Test
    @DisplayName("the flag crosses hospitals on the transfusion path only: one reader of the patient-wide queries, every shown read accounted, another hospital's text never mapped")
    void theFlagCrossesHospitalsOnTheTransfusionPathOnly() throws IOException {
        String rule = code(MAIN.resolve("service/transfusion/PositiveAntibodyScreenService.java"));
        // The patient-wide reads have one reader: this rule's service.
        for (String query : List.of("findByPatient_IdAndAntibodyScreenOrderByPerformedAtDesc(",
                "findByPatient_IdOrderByRecordedAtDesc(", "findFirstByPatient_IdAndStatusOrderByRecordedAtDesc(",
                "existsByPatient_Id(")) {
            List<String> readers = new ArrayList<>();
            try (Stream<Path> files = Files.walk(MAIN)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = code(file);
                    boolean declares = file.getFileName().toString().endsWith("Repository.java");
                    boolean antibodyQuery = query.startsWith("findByPatient_IdAndAntibodyScreen")
                        || source.contains("workupRepository." + query) || source.contains("WorkupRepository");
                    if (!declares && antibodyQuery && source.contains("." + query)) {
                        readers.add(file.getFileName().toString());
                    }
                }
            }
            assertThat(readers).as(query).containsExactly("PositiveAntibodyScreenService.java");
        }
        // The sharing choice of the patient does not narrow the flag: the rule asks no sharing policy.
        assertThat(rule).doesNotContain("RecordAccessPolicy").doesNotContain("SharingOptOut")
            .doesNotContain("readableHospitalIds");
        // Every read that SHOWS the flag is accounted; the three ways it is shown are the transfusion service's.
        assertThat(rule.split("accountReach\\(patientId, actingHospitalId, screens, workups\\);", -1)).hasSize(3);
        assertThat(rule).contains("reachRecorder.recordReach(patientId, actingHospitalId, roleValidator.getCurrentUserId(), null,");
        String service = code(SERVICE);
        assertThat(service).contains("antibodyScreen.alloImmunisedShownAt(patientId, hospitalId)")
            .contains("antibodyScreen.holdsShown(request)")
            .contains("antibodyScreen.status(patient.getId(), hospitalId, patient.getFullName())");
        // Another hospital's record is mapped by one branch, which carries four things and no text.
        int foreign = rule.indexOf("if (elsewhere(workup.getHospital(), actingHospitalId)) {");
        assertThat(foreign).isNotNegative();
        String minimum = rule.substring(foreign, rule.indexOf(".build();", foreign));
        assertThat(minimum).contains(".status(").contains(".antibodySpecificities(").contains(".recordedAt(")
            .contains(".hospitalName(").contains(".otherHospital(true)")
            .doesNotContain("getNotes").doesNotContain("getDecisionReason").doesNotContain("getReferralCentre")
            .doesNotContain("getRecordedByDisplay").doesNotContain("getTransfusionRequestId").doesNotContain(".id(");
        // And nothing of another hospital's blood group record but the time of its positive screen and its name.
        assertThat(rule).doesNotContain("getAntibodyDetail").doesNotContain("getAboGroup").doesNotContain("getRhFactor");
        // Who reads the antibody and its source: everyone on the transfusion path (the bench, the prescriber,
        // the nurse who gives the unit: a 403 there took the request off her screen), nobody else.
        String controller = code(CONTROLLER);
        int read = controller.indexOf("@GetMapping(\"/antibody-status/patient/{patientId}\")");
        assertThat(read).isNotNegative();
        assertThat(controller.substring(read, controller.indexOf("public ResponseEntity", read)))
            .contains("@PreAuthorize(READER)");
        // The flag is a field of the transfusion screens' own answers, and of nothing else that is sent.
        List<String> carriers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("payload"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (code(file).contains("alloImmunised")) {
                    carriers.add(MAIN.resolve("payload").relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        // QA D3901 (r77): and of the blood group status shown on the patient record and its safety strip, which
        // carries the flag and the antibody only (no hospital's text), filled by the accounted status read above.
        assertThat(carriers).containsExactlyInAnyOrder("dto/transfusion/AntibodyStatusResponseDTO.java",
            "dto/transfusion/PatientBloodGroupResponseDTO.java", "dto/transfusion/TransfusionRequestResponseDTO.java",
            "dto/BloodGroupStatusDTO.java");
    }
}
