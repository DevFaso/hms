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
        assertThat(assignments).contains("patientRepository.findIdsByUserId(holder.getId())",
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

    /**
     * QA D4269 review, the service side: the endpoints above are not the only way an account is switched on. The
     * e-mail link ({@code POST /auth/verify-email}, after a re-send too) activated a child's account with no age
     * check. So the check also stands on the compiled code: every method that switches a user account or a role
     * assignment on (a {@code setActive} that is not a constant false) also asks the age rule in the same method,
     * or is listed below with why no patient's own account can be switched on there.
     */
    private static final Map<String, String> ACTIVATES_WITHOUT_THE_AGE_RULE = Map.of(
        "UserServiceImpl#bootstrapFirstUser",
        "the first super-admin of an empty platform: no patient account exists yet",
        "UserServiceImpl#applyActive",
        "the administrator's update; its one caller (updateUser) asks requireOwnAccountAge before re-enabling",
        "UserRoleHospitalAssignmentMapper#updateEntity",
        "the assignment edit: updateAssignment refuses switching on a PATIENT assignment "
            + "(enforcePatientInactiveConstraint), and any other only through its code (requireActivationThroughCode)",
        "UserMapper#updateEntityFromDTO",
        "no caller in the services: the administrator's update goes through applyActive");

    private static final java.util.Set<String> ACCOUNT_OWNERS = java.util.Set.of(
        "com/example/hms/model/User", "com/example/hms/model/UserRoleHospitalAssignment");
    private static final java.util.Set<String> AGE_RULE_CALLS = java.util.Set.of(
        "requireOwnAccountAge", "requireActivatable", "mayHaveOwnAccount");

    @Test
    void everyServicePathThatSwitchesAnAccountOnAsksTheAgeFirst() throws Exception {
        java.util.Set<String> activating = new TreeSet<>();
        java.util.Set<String> asking = new java.util.HashSet<>();
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        for (org.springframework.core.io.Resource resource : resolver.getResources("classpath*:com/example/hms/**/*.class")) {
            String location = resource.getURL().toString();
            if (location.contains("/test/") || location.contains("/bootstrap/")) {
                continue;
            }
            try (java.io.InputStream in = resource.getInputStream()) {
                scan(new org.springframework.asm.ClassReader(in), activating, asking);
            }
        }
        assertThat(activating).as("the scan reads the compiled services").contains("UserServiceImpl#activateByLink");
        java.util.Set<String> unasked = new TreeSet<>(activating);
        unasked.removeAll(asking);
        unasked.removeAll(ACTIVATES_WITHOUT_THE_AGE_RULE.keySet());
        assertThat(unasked).as("a method that switches an account on asks MinorPortalRightsGuard first "
            + "(requireOwnAccountAge / requireActivatable), or is listed with its reason").isEmpty();
        assertThat(activating).as("a listed exemption whose method no longer activates is removed")
            .containsAll(ACTIVATES_WITHOUT_THE_AGE_RULE.keySet());
    }

    private static void scan(org.springframework.asm.ClassReader reader, java.util.Set<String> activating,
                             java.util.Set<String> asking) {
        String simple = reader.getClassName().substring(reader.getClassName().lastIndexOf('/') + 1).replaceFirst("[$].*", "");
        reader.accept(new org.springframework.asm.ClassVisitor(org.springframework.asm.SpringAsmInfo.ASM_VERSION) {
            @Override
            public org.springframework.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                     String signature, String[] exceptions) {
                String method = simple + "#" + name;
                return new org.springframework.asm.MethodVisitor(org.springframework.asm.SpringAsmInfo.ASM_VERSION) {
                    /** The value about to be passed is a constant false. */
                    private boolean lastFalse;

                    @Override
                    public void visitInsn(int opcode) {
                        lastFalse = opcode == org.springframework.asm.Opcodes.ICONST_0;
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        lastFalse = "java/lang/Boolean".equals(owner) && "FALSE".equals(field);
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        lastFalse = false;
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        lastFalse = false;
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        lastFalse = false;
                    }

                    @Override
                    public void visitJumpInsn(int opcode, org.springframework.asm.Label label) {
                        lastFalse = false;
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        lastFalse = false;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean itf) {
                        if ("java/lang/Boolean".equals(owner) && "valueOf".equals(called)) {
                            return;
                        }
                        if (ACCOUNT_OWNERS.contains(owner) && "setActive".equals(called) && !lastFalse) {
                            activating.add(method);
                        }
                        if (AGE_RULE_CALLS.contains(called)) {
                            asking.add(method);
                        }
                        lastFalse = false;
                    }
                };
            }
        }, org.springframework.asm.ClassReader.SKIP_DEBUG | org.springframework.asm.ClassReader.SKIP_FRAMES);
    }
}
