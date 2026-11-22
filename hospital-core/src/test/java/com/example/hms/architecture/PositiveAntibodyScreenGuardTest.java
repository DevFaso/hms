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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        String emergency = method(code, "public BloodUnitResponseDTO emergencyRelease(");
        assertThat(emergency).contains("antibodyScreen.requireEmergencyReleaseAllowed(request)")
            .contains("antibodyScreen.askRetrospectiveCrossmatch(request, unit)");
        assertThat(emergency.indexOf("antibodyScreen.requireEmergencyReleaseAllowed"))
            .isLessThan(emergency.indexOf("recordRelease("));
    }

    @Test
    @DisplayName("a crossmatch result and a new request go through the rule, and a positive screen asks the biologist")
    void theOtherTransitionsAskTheRule() throws IOException {
        String code = code(SERVICE);
        assertThat(method(code, "public CrossmatchResponseDTO recordCrossmatch("))
            .contains("antibodyScreen.requireCompatibilised(transfusionRequest, request)");
        assertThat(method(code, "public TransfusionRequestResponseDTO createRequest("))
            .contains("antibodyScreen.requireEmergencyDeclaration(request, patient.getId(), hospitalId, urgency)");
        assertThat(method(code, "public PatientBloodGroupResponseDTO recordBloodGroup("))
            .contains("antibodyScreen.askBiologistToIdentify(saved)");
        // Required injection: the rule is never silently absent in production.
        assertThat(code).containsPattern(
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
}
