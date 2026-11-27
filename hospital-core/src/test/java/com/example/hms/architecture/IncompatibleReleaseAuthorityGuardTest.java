package com.example.hms.architecture;

import com.example.hms.service.transfusion.IncompatibleReleaseDecisions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
 * Owner decision 2026-11-26, option B (V381): a release of red cells despite an incompatible crossmatch is decided
 * by the ONE release decision service, no path goes round it, and two rules hold on every path:
 *
 * <ul>
 *   <li><b>an ABO-excluded bag never leaves</b>: the block is the first thing the incompatible release rule does,
 *       it is asked again where any bag is put out, and the database refuses the row;</li>
 *   <li><b>blood is never held back for want of a physician where the hospital enabled the fallback</b>: the
 *       declaration releases the bag in the same submit, and no notice can hold or undo a release.</li>
 * </ul>
 *
 * <p>The class this guards: a second way out (a new endpoint, a « quick release ») that issues an incompatible bag
 * without the decision service, so the issuer decides alone, or an ABO-excluded bag slips out; and its mirror, a
 * check added later that makes the fallback wait for a person.
 */
@DisplayName("Every incompatible release passes the one decision service; ABO is a hard block on every path; the fallback never waits for a person")
class IncompatibleReleaseAuthorityGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String SERVICE_FILE = "service/impl/TransfusionServiceImpl.java";
    private static final String AUTHORITY_FILE = "service/transfusion/OPositiveReleaseAuthorityService.java";
    private static final String DECISIONS_FILE = "service/transfusion/IncompatibleReleaseDecisions.java";
    private static final String NOTICES_FILE = "service/transfusion/IncompatibleReleaseNotices.java";
    private static final String POLICY_FILE = "service/support/IncompatibleReleaseAuthority.java";
    private static final String SCHEDULER_FILE = "service/scheduled/OPositiveCountersignEscalationScheduler.java";
    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String main(String file) throws IOException {
        return read(MAIN.resolve(file));
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
    @DisplayName("one way in: only the release decision service asks the incompatible release rule, and only it records the decision")
    void oneDecisionService() throws IOException, NoSuchMethodException {
        assertThat(filesContaining("incompatible.authorise(")).containsExactly(AUTHORITY_FILE);
        assertThat(filesContaining("incompatible.record(")).containsExactly(AUTHORITY_FILE);
        String authority = main(AUTHORITY_FILE);
        String authorise = method(authority, "public Authorisation authorise(");
        assertThat(authorise).as("an incompatible release is put to its rule before the O positive rule answers")
            .contains("type == EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE\n            ? incompatible.authorise(request, unit, input) : null;");
        assertThat(authorise.indexOf("incompatible.authorise(")).isLessThan(authorise.indexOf("authorisePositive("));
        assertThat(method(authority, "public OPositiveReleaseDecision record("))
            .contains("incompatible.record(release, authorisation.incompatibleGrant, request, unit);");
        assertThat(authority).as("the incompatible release rule is required: no path runs without it")
            .doesNotContain("incompatible != null").doesNotContain("incompatible == null")
            .containsPattern("@org\\.springframework\\.beans\\.factory\\.annotation\\.Autowired\\s+public void setIncompatible\\(");

        // The rule cannot be asked, and its answer cannot be forged, from anywhere else.
        Method ask = IncompatibleReleaseDecisions.class.getDeclaredMethod("authorise",
            com.example.hms.model.TransfusionRequest.class, com.example.hms.model.BloodUnit.class,
            com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO.class);
        assertThat(Modifier.isPublic(ask.getModifiers()) || Modifier.isProtected(ask.getModifiers()))
            .as("authorise is package-private: the release decision service is its only caller").isFalse();
        for (Method method : IncompatibleReleaseDecisions.class.getDeclaredMethods()) {
            if (method.getName().equals("record")) {
                assertThat(Modifier.isPublic(method.getModifiers())).as("record is package-private").isFalse();
            }
        }
        for (Constructor<?> constructor : IncompatibleReleaseDecisions.Grant.class.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(constructor.getModifiers())).as("Grant has no constructor a caller can use").isTrue();
        }
        assertThat(filesContaining("new Grant(IncompatibleReleaseBasis.")).containsExactly(DECISIONS_FILE);
        assertThat(filesContaining("IncompatibleReleaseDecisions.Grant(")).isEmpty();
        assertThat(filesContaining("IncompatibleReleaseDecision.builder()")).containsExactly(DECISIONS_FILE);
        assertThat(filesContaining("incompatibleRows.save(")).containsExactlyInAnyOrder(DECISIONS_FILE, NOTICES_FILE);
    }

    @Test
    @DisplayName("the transfusion service decides nothing about an incompatible release itself, and names nobody the rule did not")
    void theTransfusionServiceOnlyAsks() throws IOException {
        String source = main(SERVICE_FILE);
        assertThat(source).as("the authoriser check moved into the decision service; no second copy")
            .doesNotContain("requireAnswerableAuthoriser")
            .doesNotContain("authoriserNotIssuer").doesNotContain("authoriserInvalid")
            .doesNotContain("IncompatibleReleaseDecisions.Grant")
            .doesNotContain("getPhysicianUnreachable()").doesNotContain("getUnreachableReason()");
        String release = method(source, "public BloodUnitResponseDTO emergencyRelease(");
        assertThat(release)
            .contains("UUID answerable = incompatibleRelease ? authorisation.incompatibleAuthoriser() : positiveAnswerable;")
            .as("a fallback release names nobody: its review item must not read an authoriser that is not there")
            .contains("if (incompatibleRelease && saved.getAuthoriser() != null) {");
        // The settings and the roles are read by the decision service only.
        assertThat(filesContaining("isIncompatibleFallbackEnabled()")).containsExactly(DECISIONS_FILE);
        assertThat(filesContaining("IncompatibleReleaseAuthority.better(")).containsExactly(DECISIONS_FILE);
        assertThat(main(POLICY_FILE)).as("the policy decides and ranks; it refuses nothing").doesNotContain("throw ");
    }

    @Test
    @DisplayName("NEVER EVENT: the ABO block is the first check of an incompatible release, is asked again wherever a bag is put out, "
        + "and nothing can switch it off")
    void aboIsAHardBlockOnEveryPath() throws IOException {
        String decisions = main(DECISIONS_FILE);
        String authorise = method(decisions, "Grant authorise(");
        int block = authorise.indexOf("requireAboCompatible(");
        assertThat(block).as("the ABO block is in authorise").isNotNegative();
        for (String later : List.of("roleValidator.", "crossmatchRepository", "settings(", "getAuthoriserUserId()",
                "getPhysicianUnreachable()", "requireAnswerableAuthoriser(", "throw new", "new Grant(", "declarerRole(")) {
            int at = authorise.indexOf(later);
            assertThat(at).as("authorise uses " + later).isNotNegative();
            assertThat(block).as("the ABO block comes before " + later).isLessThan(at);
        }
        String gate = method(decisions, "public void requireAboCompatible(");
        assertThat(gate).contains("IncompatibleReleaseAuthority.aboAllows(recipientAbo, unit.getAboGroup(), unit.getProductType())")
            .contains("throw new FieldBusinessException(FIELD_UNIT, \"transfusion.emergency.groupExcludes\"")
            .as("no setting, role or urgency is read by the block: nothing can switch it off")
            .doesNotContain("settings").doesNotContain("fallback").doesNotContain("roleValidator")
            .doesNotContain("assignmentRepository").doesNotContain("request").doesNotContain("getUrgency");
        String allows = method(main(POLICY_FILE), "public static boolean aboAllows(");
        assertThat(allows).contains("recipientAbo != null && recipientAbo.compatibleRedCellDonors().contains(donorAbo)")
            .doesNotContain("Settings").doesNotContain("fallback");

        // The one place a bag leaves the laboratory asks the block before it marks the bag as out.
        String source = main(SERVICE_FILE);
        String putOut = method(source, "private BloodUnitResponseDTO putOut(");
        int asked = putOut.indexOf("positiveAuthority.requireAboCompatible(screen != null ? screen.getAboGroup() : null, unit);");
        assertThat(asked).as("putOut asks the ABO block").isNotNegative()
            .isLessThan(putOut.indexOf("unit.setStatus(BloodUnitStatus.ISSUED)"));
        assertThat(filesContaining("setStatus(BloodUnitStatus.ISSUED)")).containsExactly(SERVICE_FILE);
        assertThat(count(source, "setStatus(BloodUnitStatus.ISSUED)")).as("one way out").isEqualTo(1);
        assertThat(method(main(AUTHORITY_FILE), "public void requireAboCompatible("))
            .contains("incompatible.requireAboCompatible(recipientAbo, unit);");

        // And the row: computed again from the two groups, refused by the database when false.
        assertThat(method(decisions, "IncompatibleReleaseDecision record("))
            .contains(".aboCompatible(IncompatibleReleaseAuthority.aboAllows(").doesNotContain(".aboCompatible(true)");
    }

    @Test
    @DisplayName("NEVER BLOCKS: where the hospital enabled the fallback the declaration releases in the same submit; "
        + "a notice cannot hold or undo a release, nor be lost")
    void theFallbackNeverWaitsForAPerson() throws IOException {
        String decisions = main(DECISIONS_FILE);
        String authorise = method(decisions, "Grant authorise(");
        assertThat(authorise).contains("return new Grant(IncompatibleReleaseBasis.AUTHORITY_PENDING, issuer, null,");
        // What the fallback asks is what the issuer has in hand: a statement, its reason, a bag. Never a second person.
        String afterDeclaration = authorise.substring(authorise.indexOf("if (!settings.fallbackEnabled())"));
        assertThat(afterDeclaration).as("the fallback names, calls and waits for nobody")
            .doesNotContain("AUTHORISER_ROLE_CODES").doesNotContain("getAuthoriserUserId").doesNotContain("userRepository")
            .doesNotContain("inBasketService").doesNotContain("notices.");
        // What can still refuse a declaration: the hospital's switch, the role of the issuer, a better bag on the bench.
        assertThat(count(afterDeclaration, "new FieldBusinessException(")).isEqualTo(3);
        assertThat(afterDeclaration).contains("transfusion.incompatible.fallback.disabled")
            .contains("transfusion.incompatible.fallback.declarerNotAllowed")
            .contains("transfusion.incompatible.fallback.notLeastIncompatible");
        assertThat(authorise).as("and the reason of the declaration, the one thing typed, asked with the other fields")
            .contains("\"unreachableReason\", \"transfusion.incompatible.fallback.reasonRequired\")");

        String record = method(decisions, "IncompatibleReleaseDecision record(");
        assertThat(record).as("the release transaction writes its row and reads nothing about a reader")
            .contains("TransactionCallbacks.afterCommit(() -> deliverNotices(decisionId))")
            .doesNotContain("throw new").doesNotContain("assignmentRepository").doesNotContain("userRepository")
            .doesNotContain("inBasketService");
        assertThat(method(decisions, "private void deliverNotices(")).contains("notices.deliver(decisionId)")
            .contains("catch (RuntimeException");

        String notices = main(NOTICES_FILE);
        assertThat(notices).contains("@Transactional(propagation = Propagation.REQUIRES_NEW)\n    public int deliver(")
            .contains("@Transactional(propagation = Propagation.REQUIRES_NEW)\n    public int escalateOverdueCountersign(")
            .as("never after the commit, where a failure would be lost").doesNotContain("createItemInNewTransaction");
        String deliver = method(notices, "public int deliver(");
        assertThat(deliver.indexOf("items.forEach(inBasketService::createItem)")).as("filed before the row is marked")
            .isNotNegative().isLessThan(deliver.indexOf("setNoticesFiledAt("));
        assertThat(deliver).as("the fallback alert is critical").contains("COUNTERSIGN_REFERENCE_TYPE,\n                InBasketPriority.CRITICAL");
        assertThat(filesContaining("setNoticesFiledAt(")).as("each rule's notices mark their own rows, and nothing else does")
            .containsExactlyInAnyOrder(NOTICES_FILE, "service/transfusion/OPositiveReleaseNotices.java");
        String scheduler = main(SCHEDULER_FILE);
        assertThat(scheduler).contains("incompatibleNotices.pendingIds()").contains("incompatibleNotices.overdueCountersignIds()")
            .contains("incompatibleNotices.deliver(decisionId)").contains("incompatibleNotices.escalateOverdueCountersign(decisionId)");
    }

    @Test
    @DisplayName("the setting is off until the laboratory director switches it on, and only laboratory roles declare")
    void offByDefaultAndTheDirectors() throws IOException {
        String policy = main(POLICY_FILE);
        assertThat(policy).contains("NO_PROTOCOL = new Settings(false, DEFAULT_DECLARER_ROLES,");
        int universe = policy.indexOf("DECLARER_ROLE_UNIVERSE = List.of(");
        assertThat(universe).isNotNegative();
        assertThat(policy.substring(universe, policy.indexOf(");", universe))).as("laboratory roles only, until the sign-off says otherwise")
            .contains("ROLE_LAB_SCIENTIST").contains("ROLE_LAB_TECHNICIAN").contains("ROLE_LAB_MANAGER").contains("ROLE_LAB_DIRECTOR")
            .doesNotContain("MIDWIFE").doesNotContain("NURSE").doesNotContain("DOCTOR");
        assertThat(main("model/OPositiveReleaseProtocol.java")).contains("private boolean incompatibleFallbackEnabled = false;");
        String setter = method(main(DECISIONS_FILE), "public IncompatibleReleaseProtocolDTO setProtocol(");
        assertThat(setter).contains("IncompatibleReleaseNotices.DIRECTOR_ROLE_CODES")
            .contains("transfusion.incompatible.protocol.directorOnly").contains("AuditEventType.CONFIGURATION_CHANGED");
        assertThat(setter.indexOf("ClientSafeAccessDeniedException")).isLessThan(setter.indexOf("protocolRepository.save("));
        assertThat(main("controller/TransfusionController.java")).contains(
            "@PutMapping(\"/incompatible-release-protocol\")\n    @PreAuthorize(\"T(com.example.hms.security.ClinicalActor).holdsOwnRole(authentication, 'LAB_DIRECTOR')\")");
    }

    @Test
    @DisplayName("the database holds the rule: never ABO-excluded, a physician who is not the issuer, the fallback only where enabled")
    void theDatabaseHoldsTheRule() throws IOException {
        String migration = read(MIGRATIONS.resolve("V381__incompatible_release_fallback.sql"));
        assertThat(migration)
            .contains("ADD COLUMN IF NOT EXISTS incompatible_fallback_enabled BOOLEAN NOT NULL DEFAULT FALSE")
            .contains("DEFAULT 'ROLE_LAB_SCIENTIST,ROLE_LAB_TECHNICIAN,ROLE_LAB_MANAGER,ROLE_LAB_DIRECTOR'")
            .contains("CHECK (abo_compatible = TRUE)")
            .contains("authoriser_user_id IS NOT NULL AND authoriser_user_id <> issued_by_user_id AND countersign_due_at IS NULL")
            .contains("fallback_enabled = TRUE AND authoriser_user_id IS NULL AND declarer_role IS NOT NULL")
            .contains("unreachable_reason IS NOT NULL AND least_incompatible = TRUE AND countersign_due_at IS NOT NULL")
            .contains("countersigned_by_user_id <> issued_by_user_id")
            .contains("notices_filed_at               TIMESTAMP");
        assertThat(migration).as("plain statements: no DO block to split").doesNotContain("DO $$");
        assertThat(read(MIGRATIONS.resolve("changelog.xml")))
            .contains("<sqlFile path=\"V381__incompatible_release_fallback.sql\"");
        // V380 is applied: it is not edited, V381 replaces its constraint.
        assertThat(read(MIGRATIONS.resolve("V380__emergency_o_positive_release_authority.sql")))
            .contains("authoriser_user_id IS NOT NULL OR release_type = 'O_POSITIVE_DECISION'");
    }
}
