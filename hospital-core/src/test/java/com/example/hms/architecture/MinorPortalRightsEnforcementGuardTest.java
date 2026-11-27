package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4269 (r81), the class: every write a patient can make on their OWN account that gives someone access,
 * changes inter-hospital sharing or changes a contact consent passes the minors' age rule
 * ({@code MinorPortalRightsGuard}). The defect was two endpoints that took no account of the age; the class is
 * the next one added the same way.
 *
 * <p>Two checks. Every such endpoint a patient can reach is listed here with the place that applies the rule,
 * and that place still applies it (a removed call fails); and an endpoint of the same kind that is not listed
 * fails, so a new one is either wired to the rule or listed as exempt with its reason.
 */
@DisplayName("QA D4269: every patient-own access, sharing and consent write applies the minors' age rule")
class MinorPortalRightsEnforcementGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern WRITE_MAPPING = Pattern.compile(
        "@(Post|Put|Delete|Patch)Mapping(?:\\(\\s*(?:(?:value|path)\\s*=\\s*)?(?:\"([^\"]*)\")?[^)]*\\))?");
    /** The words of an endpoint that gives access, changes sharing or changes a consent. */
    private static final Pattern KIND = Pattern.compile("(?i)prox|sharing|opt-out|consent|preferences");

    /** Where the rule is applied for each endpoint: the file, and the call that must stay in it. */
    private static final Map<String, String[]> ENFORCED = Map.of(
        "POST /me/patient/proxies",
        new String[]{"service/impl/PatientPortalServiceImpl.java",
            "requireMinorMay(patient, com.example.hms.service.recordaccess.MinorPortalRights.Action.GRANT_FAMILY_ACCESS);"},
        "DELETE /me/patient/proxies/{proxyId}",
        new String[]{"service/impl/PatientPortalServiceImpl.java",
            "com.example.hms.service.recordaccess.MinorPortalRights.Action.REVOKE_FAMILY_ACCESS);"},
        "PUT /me/patient/notification-preferences",
        new String[]{"service/NotificationServiceImpl.java", "requireMinorMayChangeContactConsent(userId, updates);"},
        "PUT /notifications/preferences",
        new String[]{"service/NotificationServiceImpl.java", "requireMinorMayChangeContactConsent(userId, updates);"},
        "POST /patients/{patientId}/record-sharing/opt-out",
        new String[]{"controller/RecordAccessController.java",
            "requireMinorMay(requireSelfUnlessStaff(patientId, auth, OPT_OUT_STAFF), MinorPortalRights.Action.CLOSE_SHARING);"},
        "DELETE /patients/{patientId}/record-sharing/opt-out",
        new String[]{"controller/RecordAccessController.java", "MinorPortalRights.Action.REOPEN_SHARING);"});

    /** Endpoints of the same words that are not a patient acting on their own settings, with the reason. */
    private static final Map<String, String> EXEMPT = Map.of();

    @Test
    void everyPatientOwnAccessSharingOrConsentWriteIsWiredToTheRule() throws Exception {
        List<String> found = new ArrayList<>();
        try (var files = Files.walk(MAIN.resolve("controller"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file));
                if (!source.contains("ROLE_PATIENT") && !source.contains("isAuthenticated()")) {
                    continue;
                }
                Matcher base = CLASS_MAPPING.matcher(source);
                int classAt = source.indexOf(" class ");
                String prefix = base.find() && base.start() < classAt ? base.group(1) : "";
                Matcher mapping = WRITE_MAPPING.matcher(source);
                while (mapping.find()) {
                    String path = prefix + (mapping.group(2) == null ? "" : mapping.group(2));
                    if (KIND.matcher(path).find()) {
                        found.add(mapping.group(1).toUpperCase() + " " + path);
                    }
                }
            }
        }
        assertThat(found).as("the scan reads the controllers").contains("POST /me/patient/proxies");
        List<String> unlisted = found.stream()
            .filter(endpoint -> !ENFORCED.containsKey(endpoint) && !EXEMPT.containsKey(endpoint))
            .toList();
        assertThat(unlisted)
            .as("a patient-reachable write that gives access or changes sharing or a consent must apply "
                + "MinorPortalRightsGuard (list it in ENFORCED) or be listed in EXEMPT with its reason")
            .isEmpty();
        assertThat(new TreeSet<>(found)).as("an entry whose endpoint is gone is removed from the list")
            .containsAll(ENFORCED.keySet());
    }

    @Test
    void everyListedPlaceStillAppliesTheRule() throws Exception {
        for (Map.Entry<String, String[]> entry : ENFORCED.entrySet()) {
            String source = SourceScan.stripComments(Files.readString(MAIN.resolve(entry.getValue()[0])));
            assertThat(source).as(entry.getKey()).contains(entry.getValue()[1]);
        }
    }

    @Test
    void noOwnAccountIsActivatedShownOrResentUnderTheAge() throws Exception {
        String assignments = SourceScan.stripComments(
            Files.readString(MAIN.resolve("service/UserRoleHospitalAssignmentServiceImpl.java")));
        int activation = assignments.indexOf("private void activateVerifiedAssignment(");
        assertThat(assignments.substring(activation, activation + 200))
            .as("the one place an invitation is switched on asks first").contains("requireOwnAccountAge(assignment);");
        int desk = assignments.indexOf("private UserRoleHospitalAssignment pendingPatientInvitation(");
        assertThat(assignments.substring(desk, assignments.indexOf("return assignment;", desk)))
            .as("the desk's show and re-send").contains("minorPortalRights.requireActivatable(patient);");
        assertThat(assignments).contains("LinkedPatientLookup.linkedPatient(patientRepository, holder.getId())",
            ".ifPresent(minorPortalRights::requireActivatable);");
    }

    @Test
    void anAccessGivenDuringMinorityIsReadAsEndedByTheOneGateOfEveryFamilyRead() throws Exception {
        String portal = SourceScan.stripComments(
            Files.readString(MAIN.resolve("service/impl/PatientPortalServiceImpl.java")));
        int expired = portal.indexOf("private static boolean isProxyExpired(PatientProxy proxy)");
        assertThat(portal.substring(expired, expired + 300)).contains("|| lapsedAtMajority(proxy);");
        int gate = portal.indexOf("private Patient verifyProxyAccess(");
        assertThat(portal.substring(gate, gate + 900)).contains("if (isProxyExpired(proxy))");
    }
}
