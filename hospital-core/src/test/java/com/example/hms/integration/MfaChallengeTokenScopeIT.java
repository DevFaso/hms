package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.enums.MfaMethodType;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserMfaEnrollment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.oidc.KeycloakJwtFixture;
import com.example.hms.service.MfaService;
import com.example.hms.utility.MessageUtil;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The MFA challenge token {@code POST /auth/login} hands a hospital admin,
 * through the REAL security filter chain: it completes the MFA step (the
 * verify, and a FIRST enrolment) and authenticates nothing else, and a
 * verified authenticator is replaced only by a full session with a current
 * code in {@code X-Mfa-Token}.
 */
@AutoConfigureMockMvc
@Import(OidcResourceServerIntegrationTest.OidcTestConfig.class)
class MfaChallengeTokenScopeIT extends BaseIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "Challenge-Pass-1";
    private static final String ROLE = "ROLE_HOSPITAL_ADMIN";
    private static final String STEP_UP = "X-Mfa-Token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MfaService mfaService;
    @Autowired private KeycloakJwtFixture keycloak;
    @Autowired private IdleSessionTracker idleSessionTracker;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private UserMfaEnrollmentRepository enrollmentRepository;
    @Autowired private MfaBackupCodeRepository backupCodeRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private com.example.hms.security.LoginAttemptStore attemptStore;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();

    private Organization organization;
    private Hospital hospital;
    /** A hospital admin with a verified authenticator. */
    private User enrolled;
    private String enrolledSecret;
    private List<String> enrolledBackupCodes;

    @BeforeEach
    void setUp() throws Exception {
        organization = organizationRepository.save(Organization.builder()
            .name("MFA Scope Network " + next())
            .code("MSN" + next())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        String id = next();
        hospital = hospitalRepository.save(Hospital.builder()
            .name("MFA Scope Hospital " + id)
            .code("MS" + id)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226558" + id)
            .email("mfa-scope" + id + "@hospital.test")
            .organization(organization)
            .build());
        enrolled = account("mfaadm");
        MfaService.MfaEnrollmentResult first = mfaService.enrollTotp(enrolled, null, null);
        enrolledSecret = first.secret();
        enrolledBackupCodes = first.backupCodes();
        assertThat(mfaService.verifyEnrollment(enrolled.getId(), code(enrolledSecret), null)).isTrue();
    }

    @AfterEach
    void tearDown() {
        createdUsers.forEach(id -> attemptStore.clear("stepup:" + id));
        auditEventLogRepository.deleteAllInBatch();
        backupCodeRepository.deleteAll(backupCodeRepository.findAll().stream()
            .filter(c -> createdUsers.contains(c.getUserId())).toList());
        for (UUID userId : createdUsers) {
            enrollmentRepository.deleteAll(enrollmentRepository.findByUserId(userId));
            assignmentRepository.deleteAll(assignmentRepository.findByUserId(userId));
        }
        userRepository.deleteAllById(createdUsers);
        roleRepository.deleteAllById(createdRoles);
        hospitalRepository.delete(hospital);
        organizationRepository.delete(organization);
    }

    // ── What the challenge token may NOT do ──

    @Test
    @DisplayName("the challenge token cannot replace an active authenticator, even with a current code (403)")
    void challengeCannotReEnrol() throws Exception {
        String challenge = challengeFor(enrolled, true);

        assertThat(refusal(perform(post("/auth/mfa/enroll"), challenge, null))).isEqualTo("mfa.stepup.challenge");
        assertThat(refusal(perform(post("/auth/mfa/enroll").header(STEP_UP, code(enrolledSecret)), challenge, null)))
            .isEqualTo("mfa.stepup.challenge");

        assertThat(secretOf(enrolled)).isEqualTo(enrolledSecret);
        assertThat(pendingOf(enrolled)).isNull();
        assertThat(mfaService.isMfaEnabled(enrolled.getId())).isTrue();
    }

    @Test
    @DisplayName("the challenge token cannot rewrite or delete the MFA records")
    void challengeCannotRewriteMfaRecords() throws Exception {
        String challenge = challengeFor(enrolled, true);

        MvcResult refused = perform(put("/auth/credentials/mfa"), challenge, List.of());
        assertThat(refused.getResponse().getStatus()).isEqualTo(401);
        // Refused as no caller at all, before the step-up is even asked for.
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("mfa.stepup");

        assertThat(mfaService.isMfaEnabled(enrolled.getId())).isTrue();
        assertThat(secretOf(enrolled)).isEqualTo(enrolledSecret);
    }

    @Test
    @DisplayName("the challenge token is no session: /auth/me, the account endpoints, ws-ticket and the API answer 401")
    void challengeIsNoSession() throws Exception {
        String challenge = challengeFor(enrolled, true);

        assertThat(status(post("/auth/me/change-password"), challenge,
            Map.of("currentPassword", PASSWORD, "newPassword", "Hijacked-Pass-2")))
            .isEqualTo(401);
        assertThat(passwordEncoder.matches(PASSWORD,
            userRepository.findById(enrolled.getId()).orElseThrow().getPasswordHash())).isTrue();
        assertThat(status(get("/auth/credentials/me"), challenge, null)).isEqualTo(401);
        assertThat(status(get("/auth/session/bootstrap"), challenge, null)).isEqualTo(401);
        assertThat(status(get("/auth/mfa/status"), challenge, null)).isEqualTo(401);
        assertThat(status(post("/auth/ws-ticket"), challenge, null)).isEqualTo(401);
        assertThat(status(get("/me/assignments"), challenge, null)).isEqualTo(401);
    }

    @Test
    @DisplayName("the challenge token is no refresh token: it cannot be exchanged for a session")
    void challengeIsNoRefreshToken() throws Exception {
        String challenge = challengeFor(enrolled, true);

        MvcResult refreshed = refresh(challenge);

        assertThat(refreshed.getResponse().getStatus()).isEqualTo(401);
        assertThat(refreshed.getResponse().getContentAsString()).doesNotContain("accessToken");
    }

    @Test
    @DisplayName("the challenge token is single-use: a replay is refused, and so is its use as a bearer afterwards")
    void challengeIsSingleUse() throws Exception {
        String challenge = challengeFor(enrolled, true);
        String code = code(enrolledSecret);

        assertThat(verify(challenge, code).getResponse().getStatus()).isEqualTo(200);

        MvcResult replay = verify(challenge, code);
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        assertThat(replay.getResponse().getContentAsString()).doesNotContain("accessToken");
        assertThat(status(post("/auth/mfa/enroll"), challenge, null)).isEqualTo(401);
    }

    @Test
    @DisplayName("refresh takes only a refresh token: an access token is refused, a real refresh token still rotates")
    void refreshTakesOnlyARefreshToken() throws Exception {
        JsonNode session = verified(enrolled, enrolledSecret);

        assertThat(refresh(session.get("accessToken").asString()).getResponse().getStatus()).isEqualTo(401);

        MvcResult rotated = refresh(session.get("refreshToken").asString());
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(rotated).get("accessToken").asString()).isNotBlank();
    }

    @Test
    @DisplayName("a refresh token is no access token: as a bearer it is refused")
    void refreshTokenIsNoBearer() throws Exception {
        JsonNode session = verified(enrolled, enrolledSecret);

        assertThat(status(get("/me/assignments"), session.get("refreshToken").asString(), null)).isEqualTo(401);
        assertThat(status(get("/me/assignments"), session.get("accessToken").asString(), null)).isEqualTo(200);
    }

    // ── What it still does ──

    @Test
    @DisplayName("the challenge token still completes the MFA step, and the session it yields works")
    void challengeStillCompletesVerify() throws Exception {
        String access = fullSession(enrolled, enrolledSecret);

        assertThat(status(get("/me/assignments"), access, null)).isEqualTo(200);
    }

    @Test
    @DisplayName("a first enrolment during login still works with the challenge token, and only once")
    void firstEnrolmentDuringLogin() throws Exception {
        User fresh = account("mfanew");
        idleSessionTracker.clear(fresh.getId());
        String challenge = challengeFor(fresh, false);

        MvcResult started = perform(post("/auth/mfa/enroll"), challenge, null);
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        String secret = body(started).get("secret").asString();
        assertThat(status(post("/auth/mfa/verify-enrollment"), challenge, Map.of("code", code(secret))))
            .isEqualTo(200);
        assertThat(mfaService.isMfaEnabled(fresh.getId())).isTrue();

        // Now that it is active, the same challenge token cannot replace it.
        assertThat(refusal(perform(post("/auth/mfa/enroll"), challenge, null))).isEqualTo("mfa.stepup.challenge");
        assertThat(secretOf(fresh)).isEqualTo(secret);
    }

    @Test
    @DisplayName("a first enrolment left half done can be started again from a new login")
    void halfDoneFirstEnrolmentRestarts() throws Exception {
        User fresh = account("mfahlf");
        idleSessionTracker.clear(fresh.getId());
        assertThat(status(post("/auth/mfa/enroll"), challengeFor(fresh, false), null)).isEqualTo(200);

        // The page was closed before the first code: the next login starts over.
        MvcResult again = perform(post("/auth/mfa/enroll"), challengeFor(fresh, false), null);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(secretOf(fresh)).isEqualTo(body(again).get("secret").asString());
    }

    @Test
    @DisplayName("two concurrent verifies of one challenge yield exactly one session")
    void concurrentVerifiesOfOneChallengeYieldOneSession() throws Exception {
        String challenge = challengeFor(enrolled, true);
        String code = code(enrolledSecret);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> attempt = () -> {
                go.await();
                return verify(challenge, code).getResponse().getStatus();
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            go.countDown();
            List<Integer> statuses = List.of(first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                second.get(30, java.util.concurrent.TimeUnit.SECONDS));

            assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Replacing an active authenticator ──

    @Test
    @DisplayName("a verified authenticator that is disabled now is no first enrolment: the challenge cannot restart it")
    void disabledVerifiedAuthenticatorIsNotRestartedFromTheChallenge() throws Exception {
        UserMfaEnrollment row = enrollmentRepository.findByUserIdAndMethod(enrolled.getId(), MfaMethodType.TOTP)
            .orElseThrow();
        row.setEnabled(false);
        enrollmentRepository.save(row);

        // MFA no longer signs in, so login offers enrolment; the challenge still cannot take it.
        String challenge = challengeFor(enrolled, false);
        assertThat(refusal(perform(post("/auth/mfa/enroll"), challenge, null))).isEqualTo("mfa.stepup.challenge");

        assertThat(secretOf(enrolled)).isEqualTo(enrolledSecret);
        assertThat(pendingOf(enrolled)).isNull();
    }

    @Test
    @DisplayName("wrong step-up codes lock the step-up for the user: even a correct code is refused for a while")
    void wrongStepUpCodesLockTheStepUp() throws Exception {
        String access = fullSession(enrolled, enrolledSecret);
        for (int i = 0; i < 5; i++) {
            assertThat(refusal(perform(post("/auth/mfa/enroll").header(STEP_UP, "ABCDEF"), access, null)))
                .isEqualTo("mfa.stepup.invalid");
        }

        assertThat(refusal(perform(post("/auth/mfa/enroll").header(STEP_UP, code(enrolledSecret)), access, null)))
            .isEqualTo("mfa.stepup.locked");
        assertThat(pendingOf(enrolled)).isNull();
    }

    @Test
    @DisplayName("a step-up inside a long caller transaction does not hold the actor's MFA row: sign-in still works")
    void stepUpDoesNotHoldTheMfaRowForTheCallersTransaction() throws Exception {
        java.util.concurrent.CountDownLatch steppedUp = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        String code = code(enrolledSecret);
        try {
            var caller = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(
                transactionManager).execute(status -> {
                    boolean verified = mfaService.requireStepUp(enrolled.getId(), code, "test action", true);
                    steppedUp.countDown();
                    try {
                        release.await(60, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return verified;
                }));
            assertThat(steppedUp.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // The caller's transaction is still open: signing in writes the same row.
            String challenge = challengeFor(enrolled, true);
            var signIn = pool.submit(() -> verify(challenge, code(enrolledSecret)).getResponse().getStatus());
            assertThat(signIn.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);

            release.countDown();
            assertThat(caller.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a full session replaces it only with the step-up: none or a wrong code answers 403 with a reason")
    void fullSessionReplacesOnlyWithTheStepUp() throws Exception {
        String access = fullSession(enrolled, enrolledSecret);

        assertThat(refusal(perform(post("/auth/mfa/enroll"), access, null))).isEqualTo("mfa.stepup.required");
        assertThat(refusal(perform(post("/auth/mfa/enroll").header(STEP_UP, "ABCDEF"), access, null)))
            .isEqualTo("mfa.stepup.invalid");
        assertThat(pendingOf(enrolled)).isNull();

        MvcResult started = perform(post("/auth/mfa/enroll").header(STEP_UP, code(enrolledSecret)), access, null);
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        assertThat(pendingOf(enrolled)).isEqualTo(body(started).get("secret").asString());
        assertThat(secretOf(enrolled)).isEqualTo(enrolledSecret);
    }

    @Test
    @DisplayName("an unused backup code is a step-up too, and it is spent")
    void backupCodeIsAStepUp() throws Exception {
        String access = fullSession(enrolled, enrolledSecret);
        String backup = enrolledBackupCodes.get(0);

        assertThat(status(post("/auth/mfa/enroll").header(STEP_UP, backup), access, null)).isEqualTo(200);
        assertThat(refusal(perform(post("/auth/mfa/enroll").header(STEP_UP, backup), access, null)))
            .isEqualTo("mfa.stepup.invalid");
    }

    @Test
    @DisplayName("a replacement left half done changes nothing: the current authenticator keeps signing in")
    void halfDoneReplacementLeavesTheCurrentAuthenticator() throws Exception {
        String access = fullSession(enrolled, enrolledSecret);
        MvcResult started = perform(post("/auth/mfa/enroll").header(STEP_UP, code(enrolledSecret)), access, null);
        assertThat(started.getResponse().getStatus()).isEqualTo(200);
        String pendingBackup = body(started).get("backupCodes").get(0).asString();

        // Abandoned before the first code from the new one.
        assertThat(mfaService.isMfaEnabled(enrolled.getId())).isTrue();
        String challenge = challengeFor(enrolled, true);
        assertThat(refusal(perform(post("/auth/mfa/enroll"), challenge, null))).isEqualTo("mfa.stepup.challenge");
        // The pending replacement's backup codes do not sign in; the current ones still do.
        assertThat(verify(challenge, pendingBackup).getResponse().getStatus()).isEqualTo(401);
        assertThat(verify(challenge, enrolledBackupCodes.get(1)).getResponse().getStatus()).isEqualTo(200);
        assertThat(verify(challengeFor(enrolled, true), code(enrolledSecret)).getResponse().getStatus())
            .isEqualTo(200);
        assertThat(secretOf(enrolled)).isEqualTo(enrolledSecret);
    }

    @Test
    @DisplayName("confirming the replacement swaps the authenticators: the new one signs in, the old one no longer does")
    void confirmedReplacementSwaps() throws Exception {
        String access = fullSession(enrolled, enrolledSecret);
        MvcResult started = perform(post("/auth/mfa/enroll").header(STEP_UP, code(enrolledSecret)), access, null);
        String replacement = body(started).get("secret").asString();

        assertThat(status(post("/auth/mfa/verify-enrollment"), access, Map.of("code", code(replacement))))
            .isEqualTo(200);

        assertThat(secretOf(enrolled)).isEqualTo(replacement);
        assertThat(pendingOf(enrolled)).isNull();
        assertThat(verify(challengeFor(enrolled, true), code(enrolledSecret)).getResponse().getStatus())
            .isEqualTo(401);
        assertThat(verify(challengeFor(enrolled, true), code(replacement)).getResponse().getStatus())
            .isEqualTo(200);
    }

    @Test
    @DisplayName("a full session rewrites the MFA records only with the step-up")
    void fullSessionRewritesMfaRecordsWithTheStepUp() throws Exception {
        String access = fullSession(enrolled, enrolledSecret);

        assertThat(refusal(perform(put("/auth/credentials/mfa"), access, List.of()))).isEqualTo("mfa.stepup.required");
        assertThat(mfaService.isMfaEnabled(enrolled.getId())).isTrue();

        assertThat(status(put("/auth/credentials/mfa").header(STEP_UP, code(enrolledSecret)), access, List.of()))
            .isEqualTo(200);
        assertThat(mfaService.isMfaEnabled(enrolled.getId())).isFalse();
    }

    @Test
    @DisplayName("a Keycloak token is untouched by the challenge confinement")
    void keycloakPathUnaffected() throws Exception {
        String kc = keycloak.mintToken(KeycloakJwtFixture.TokenSpec
            .defaults(OidcResourceServerIntegrationTest.TEST_ISSUER, OidcResourceServerIntegrationTest.TEST_AUDIENCE)
            .withRealmRoles(List.of("PATIENT", "offline_access", "uma_authorization", "default-roles-hms")));

        // It reaches the controller, which refuses the change because Keycloak
        // owns the address: not a 401 from the filter.
        MvcResult reached = perform(post("/auth/me/change-email").header(HttpHeaders.ACCEPT_LANGUAGE, "en"), kc,
            Map.of("currentPassword", PASSWORD, "newEmail", "kc" + next() + "@self.test"));
        assertThat(reached.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(reached).get("message").asString())
            .isEqualTo(MessageUtil.resolve("user.email.change.external"));
    }

    // ── helpers ──

    /** {@code POST /auth/login} with the password only: the MFA challenge token. */
    private String challengeFor(User user, boolean expectEnrolled) throws Exception {
        MvcResult login = mockMvc.perform(post("/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", user.getUsername(), "password", PASSWORD, "selectedRole", ROLE))))
            .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = body(login);
        assertThat(body.get("mfaRequired").asBoolean()).isTrue();
        assertThat(body.get("mfaEnrolled").asBoolean()).isEqualTo(expectEnrolled);
        assertThat(body.hasNonNull("accessToken")).isFalse();
        return body.get("mfaToken").asString();
    }

    /** The password, then the code: the full session the challenge completes. */
    private JsonNode verified(User user, String secret) throws Exception {
        String challenge = challengeFor(user, true);
        MvcResult verified = mockMvc.perform(post("/auth/mfa/verify").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("mfaToken", challenge, "code", code(secret)))))
            .andReturn();
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        return body(verified);
    }

    private String fullSession(User user, String secret) throws Exception {
        return verified(user, secret).get("accessToken").asString();
    }

    /** {@code POST /auth/mfa/verify}: the challenge token in the body, never as a bearer. */
    private MvcResult verify(String challenge, String code) throws Exception {
        return mockMvc.perform(post("/auth/mfa/verify").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("mfaToken", challenge, "code", code))))
            .andReturn();
    }

    /** A refused step-up: 403, never 401, and its reason code. */
    private String refusal(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
            .as(result.getResponse().getContentAsString()).isEqualTo(403);
        return body(result).get("code").asString();
    }

    private String pendingOf(User user) {
        return enrollmentRepository.findByUserIdAndMethod(user.getId(), MfaMethodType.TOTP)
            .map(UserMfaEnrollment::getPendingTotpSecret).orElse(null);
    }

    /** {@code POST /auth/token/refresh} with the token in the body, as the native apps send it. */
    private MvcResult refresh(String token) throws Exception {
        return mockMvc.perform(post("/auth/token/refresh").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", token))))
            .andReturn();
    }

    private static String code(String secret) throws Exception {
        return new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
            .generate(secret, System.currentTimeMillis() / 1000 / 30);
    }

    private String secretOf(User user) {
        return enrollmentRepository.findByUserIdAndMethod(user.getId(), MfaMethodType.TOTP)
            .map(UserMfaEnrollment::getTotpSecret).orElse(null);
    }

    private String next() {
        return String.format("%05d", SEQUENCE.incrementAndGet());
    }

    private Role ensureRole(String code) {
        return roleRepository.findByCode(code).orElseGet(() -> {
            Role saved = roleRepository.save(Role.builder()
                .name(code.substring("ROLE_".length()))
                .code(code)
                .description(code)
                .build());
            createdRoles.add(saved.getId());
            return saved;
        });
    }

    private User account(String prefix) {
        String suffix = next();
        User user = userRepository.save(User.builder()
            .username(prefix + suffix)
            .passwordHash(passwordEncoder.encode(PASSWORD))
            .email(prefix + suffix + "@mfa-scope.test")
            .firstName(prefix)
            .lastName("Scope" + suffix)
            .phoneNumber("+22679" + suffix)
            .isActive(true)
            .build());
        createdUsers.add(user.getId());
        // The user is active elsewhere, so the idle gate (on in tests) never
        // answers for the refusals below; the first-enrolment cases clear it.
        idleSessionTracker.touch(user.getId());
        assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("MS-" + next())
            .description(ROLE)
            .user(user)
            .hospital(hospital)
            .role(ensureRole(ROLE))
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        return user;
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String bearer, Object body) throws Exception {
        request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).with(csrf());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private int status(MockHttpServletRequestBuilder request, String bearer, Object body) throws Exception {
        return perform(request, bearer, body).getResponse().getStatus();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
