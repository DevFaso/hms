package com.example.hms.architecture;

import com.example.hms.service.transfusion.OPositiveReleaseAuthorityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner decision 2026-11-25 (V380, QA r77 « the server lets the issuing scientist take the O+ decision »): who
 * decides an emergency O RhD positive release is answered by ONE service, and no way a red-cell bag leaves the
 * laboratory uncrossmatched or on an emergency decision goes round it.
 *
 * <p>The class this guards: a second release path (a new endpoint, a batch, a « quick issue ») that puts a bag
 * out without asking the decision service, so the issuer decides alone again and no review is filed; and its
 * mirror, a check added to the decision service that holds blood back for want of a person.
 */
@DisplayName("Every emergency red-cell release passes the one O positive decision service, which never holds blood for want of a person")
class EmergencyOPositiveAuthorityGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String SERVICE_FILE = "service/impl/TransfusionServiceImpl.java";
    private static final String AUTHORITY_FILE = "service/transfusion/OPositiveReleaseAuthorityService.java";
    private static final String POLICY_FILE = "service/support/OPositiveReleaseAuthority.java";
    private static final String NOTICES_FILE = "service/transfusion/OPositiveReleaseNotices.java";
    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static List<String> filesContaining(String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (read(file).contains(needle)) {
                    hits.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return hits;
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).as(signature).isNotNegative();
        int end = source.indexOf("\n    }\n", start);
        return source.substring(start, end);
    }

    @Test
    @DisplayName("both ways out ask the decision service before the bag is put out, and hand its answer to the release row")
    void everyWayOutIsAuthorised() throws IOException {
        String source = read(MAIN.resolve(SERVICE_FILE));
        for (String signature : List.of("public BloodUnitResponseDTO issueUnit(", "public BloodUnitResponseDTO emergencyRelease(")) {
            String body = method(source, signature);
            int authorised = body.indexOf("positiveAuthority.authorise(");
            int recorded = body.indexOf("recordRelease(");
            int out = body.indexOf("putOut(request, unit)");
            assertThat(authorised).as(signature + " asks the decision service").isNotNegative();
            assertThat(recorded).as(signature + " records after it is authorised").isGreaterThan(authorised);
            assertThat(out).as(signature + " puts the bag out last").isGreaterThan(recorded);
            assertThat(count(body, "putOut(")).as(signature + " has one way out").isEqualTo(1);
        }
        // The two callers above are the only ones: EmergencyBloodReleaseGuardTest holds putOut to them.
        assertThat(count(source, "positiveAuthority.authorise(")).isEqualTo(2);
        assertThat(count(source, "recordRelease(")).as("defined once, called by the two ways out").isEqualTo(3);
    }

    @Test
    @DisplayName("a release row cannot be written without the decision service's answer, and its decision is kept with it")
    void theReleaseRowNeedsTheAuthorisation() throws IOException {
        String source = read(MAIN.resolve(SERVICE_FILE));
        String record = method(source, "private EmergencyBloodRelease recordRelease(");
        assertThat(record).contains("OPositiveReleaseAuthorityService.Authorisation authorisation")
            .contains("releaseRepository.save(EmergencyBloodRelease.builder()")
            .contains("positiveAuthority.record(saved, authorisation, request, unit,");
        // An O negative release reads nothing of this rule: the assessment is made for O positive bags only.
        assertThat(count(source, "positiveAssessmentFor(request, unit)")).as("the two ways out").isEqualTo(2);
        assertThat(method(source, "Assessment positiveAssessmentFor(")).contains("AboGroup.isEmergencyOPositive(").contains(": null");
        assertThat(filesContaining("EmergencyBloodRelease.builder()")).containsExactly(SERVICE_FILE);
        assertThat(count(source, "EmergencyBloodRelease.builder()")).isEqualTo(1);
        assertThat(filesContaining("setStatus(BloodUnitStatus.ISSUED)")).containsExactly(SERVICE_FILE);
    }

    @Test
    @DisplayName("the answer is built by the decision service only, and the transfusion service cannot skip it")
    void theAnswerCannotBeForged() throws IOException {
        for (Constructor<?> constructor : OPositiveReleaseAuthorityService.Authorisation.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(constructor.getModifiers())).as("Authorisation has no constructor a caller can use").isTrue();
        }
        assertThat(filesContaining("new Authorisation(")).containsExactly(AUTHORITY_FILE);
        assertThat(filesContaining("OPositiveReleaseDecision.builder()")).containsExactly(AUTHORITY_FILE);
        // The row is written by the decision service; its notices mark it filed, or escalated, and nothing else.
        assertThat(filesContaining("decisionRepository.save(")).containsExactlyInAnyOrder(AUTHORITY_FILE, NOTICES_FILE);

        String source = read(MAIN.resolve(SERVICE_FILE));
        assertThat(source).as("the decision service is required: no path runs without it")
            .doesNotContain("positiveAuthority != null").doesNotContain("positiveAuthority == null")
            .containsPattern("@org\\.springframework\\.beans\\.factory\\.annotation\\.Autowired\\s+public void setPositiveAuthority\\(");
    }

    @Test
    @DisplayName("the table is read in one place: the tier of a patient is decided by the policy, applied by the decision service")
    void theTableLivesInOnePlace() throws IOException {
        assertThat(filesContaining("OPositiveReleaseAuthority.tierOf(")).containsExactly(AUTHORITY_FILE);
        assertThat(filesContaining("OPositiveReleaseAuthority.plainIssueAllowed(")).containsExactly(AUTHORITY_FILE);
        assertThat(filesContaining("OPositiveReleaseAuthority.oNegativeFirst(")).containsExactly(AUTHORITY_FILE);
        assertThat(filesContaining("positiveAuthority.assess(")).containsExactly(SERVICE_FILE);
        String policy = read(MAIN.resolve(POLICY_FILE));
        assertThat(policy).as("the policy decides a tier and refuses nothing").doesNotContain("throw ");
        assertThat(filesContaining("EmergencyReleasePolicy.oPositiveStep(")).as("the rule of 2026-11-06 it replaces is gone").isEmpty();
    }

    @Test
    @DisplayName("NEVER BLOCKS: no physician reachable is answered before anything is asked; a notice cannot hold or undo a release, nor be lost")
    void neverHoldsBloodForWantOfAPerson() throws IOException {
        String authority = read(MAIN.resolve(AUTHORITY_FILE));
        String physician = method(authority, "private UUID physician(");
        int unreachable = physician.indexOf("return null;");
        assertThat(unreachable).as("stating that nobody can be reached releases the bag").isNotNegative()
            .isLessThan(physician.indexOf("requireNamed("))
            .isLessThan(physician.indexOf("throw new"));
        assertThat(physician).contains("input.getPhysicianUnreachable()");

        String authorise = method(authority, "public Authorisation authorise(");
        assertThat(authorise).as("the pending authority is a release, not a refusal")
            .contains("OPositiveDecisionBasis.AUTHORITY_PENDING");
        assertThat(count(authorise, "throw new")).as("the one refusal of authorise sends a plain issue to the documented release")
            .isEqualTo(1);
        assertThat(authorise).contains("transfusion.emergency.decisionRequired");

        String record = method(authority, "public OPositiveReleaseDecision record(");
        assertThat(record).as("the release transaction writes its rows and reads nothing about a reader")
            .contains("TransactionCallbacks.afterCommit(() -> deliverNotices(decisionId))")
            .doesNotContain("throw new").doesNotContain("assignmentRepository").doesNotContain("userRepository")
            .doesNotContain("inBasketService");
        assertThat(method(authority, "private void deliverNotices(")).contains("notices.deliver(decisionId)")
            .contains("catch (RuntimeException");
        assertThat(method(authority, "private void auditAfterCommit(")).contains("TransactionCallbacks.afterCommit(")
            .contains("catch (RuntimeException");

        // The notices: a transaction of their own, the items filed in it with the mark, so a failure is retried.
        String notices = read(MAIN.resolve(NOTICES_FILE));
        String deliver = method(notices, "public int deliver(");
        assertThat(notices).contains("@Transactional(propagation = Propagation.REQUIRES_NEW)\n    public int deliver(");
        assertThat(deliver.indexOf("items.forEach(inBasketService::createItem)")).as("filed before the row is marked")
            .isNotNegative().isLessThan(deliver.indexOf("setNoticesFiledAt("));
        assertThat(notices).as("never after the commit, where a failure would be lost")
            .doesNotContain("createItemInNewTransaction");
        assertThat(filesContaining("setNoticesFiledAt(")).containsExactly(NOTICES_FILE);
    }

    @Test
    @DisplayName("the database refuses what the rule forbids: the issuer alone outside the low-risk tier, a physician who is the issuer")
    void theDatabaseHoldsTheRule() throws IOException {
        String migration = read(MIGRATIONS.resolve("V380__emergency_o_positive_release_authority.sql"));
        assertThat(migration)
            .contains("CONSTRAINT ck_o_positive_decision_issuer_alone CHECK (decision_basis <> 'PROTOCOL'")
            .contains("risk_tier = 'LOW_RISK' AND protocol_active = TRUE")
            .contains("decided_by_user_id IS DISTINCT FROM issued_by_user_id")
            .contains("notices_filed_at            TIMESTAMP")
            // A pending O positive decision names nobody; an incompatible release still must.
            .contains("authoriser_user_id IS NOT NULL OR release_type = 'O_POSITIVE_DECISION'")
            .contains("CONSTRAINT ck_o_positive_decision_countersign  CHECK (risk_tier = 'LOW_RISK' OR countersign_due_at IS NOT NULL)");
        assertThat(read(MIGRATIONS.resolve("changelog.xml")))
            .contains("<sqlFile path=\"V380__emergency_o_positive_release_authority.sql\"");
    }
}
