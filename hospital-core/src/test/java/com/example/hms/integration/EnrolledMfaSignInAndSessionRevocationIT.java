package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.model.User;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.tenant.LinkedTestAccounts;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Through the REAL security chain:
 *
 * <ul>
 *   <li>QA D2182: an account that enrolled an authenticator is asked for the
 *       code at every password sign-in, whatever its roles (a nurse and a
 *       patient: neither is in {@code app.mfa.required-roles}). Wrong and
 *       reused codes, and the login-code lockout, apply exactly as for a
 *       required role. An account with no authenticator is not asked.</li>
 *   <li>QA D2184: logout ends the whole sign-in session: an access token a
 *       refresh superseded is refused at once, as is the refresh chain, while
 *       the same user's other session stays alive.</li>
 * </ul>
 */
@AutoConfigureMockMvc
class EnrolledMfaSignInAndSessionRevocationIT extends BaseIT {

    private static final String PASSWORD = "Enrolled#Mfa2026";
    private static final String CSRF = "enrolled-mfa-csrf";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserMfaEnrollmentRepository enrollmentRepository;
    @Autowired private MfaBackupCodeRepository backupCodeRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private final List<UUID> mfaUsers = new ArrayList<>();
    private LinkedTestAccounts accounts;
    private UUID hospitalId;

    @BeforeEach
    void setUp() {
        accounts = new LinkedTestAccounts(hospitalRepository, userRepository, roleRepository,
            assignmentRepository, auditEventLogRepository);
        hospitalId = accounts.hospital("Enrolled MFA Hospital").getId();
    }

    @AfterEach
    void tearDown() {
        for (UUID userId : mfaUsers) {
            backupCodeRepository.deleteAll(backupCodeRepository.findByUserIdAndPending(userId, false));
            backupCodeRepository.deleteAll(backupCodeRepository.findByUserIdAndPending(userId, true));
            enrollmentRepository.deleteAll(enrollmentRepository.findByUserId(userId));
        }
        mfaUsers.clear();
        accounts.cleanUp();
    }

    // ── D2182 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D2182: a role outside required-roles is not asked before enrolling, and always asked after")
    void enrolledUserIsAskedWhateverTheRole() throws Exception {
        for (String role : List.of("NURSE", "PATIENT")) {
            User user = withPassword(accounts.userAt(role.toLowerCase(), hospitalId, role));

            // Not enrolled, not a required role: straight in.
            JsonNode first = json(login(user));
            assertThat(first.path("mfaRequired").asBoolean(false)).as(role).isFalse();
            String accessToken = first.get("accessToken").asString();
            assertThat(accessToken).isNotBlank();

            String secret = enrol(user, accessToken);

            JsonNode challenge = json(login(user));
            assertThat(challenge.get("mfaRequired").asBoolean()).as(role).isTrue();
            assertThat(challenge.get("mfaEnrolled").asBoolean()).as(role).isTrue();
            assertThat(challenge.path("accessToken").isMissingNode() || challenge.get("accessToken").isNull())
                .as(role + ": no session before the code").isTrue();
            String mfaToken = challenge.get("mfaToken").asString();

            // A wrong code: 401, no tokens, and the challenge still usable.
            MvcResult wrong = verify(mfaToken, wrongCode(secret));
            assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
            assertThat(wrong.getResponse().getContentAsString()).doesNotContain("accessToken\":\"ey");

            String code = currentCode(secret);
            JsonNode session = json(verify(mfaToken, code));
            String verified = session.get("accessToken").asString();
            assertThat(verified).isNotBlank();
            assertThat(jwtTokenProvider.secondFactorAt(verified)).as(role).isNotNull();
            assertThat(as(verified, get("/auth/session/bootstrap")).getResponse().getStatus()).isEqualTo(200);

            // The same code again, on a fresh challenge: refused (replay).
            String again = json(login(user)).get("mfaToken").asString();
            MvcResult replay = verify(again, code);
            assertThat(replay.getResponse().getStatus()).as(role + " replay").isEqualTo(401);
        }
    }

    @Test
    @DisplayName("D2182: the login-code lockout applies to an enrolled user outside required-roles")
    void loginCodeLockoutApplies() throws Exception {
        User nurse = withPassword(accounts.userAt("nurse", hospitalId, "NURSE"));
        String secret = enrol(nurse, json(login(nurse)).get("accessToken").asString());

        String mfaToken = json(login(nurse)).get("mfaToken").asString();
        for (int i = 0; i < 5; i++) {
            assertThat(verify(mfaToken, wrongCode(secret)).getResponse().getStatus()).isEqualTo(401);
        }
        // Locked: even the right code is refused.
        assertThat(verify(mfaToken, currentCode(secret)).getResponse().getStatus()).isEqualTo(429);
    }

    // ── D2184 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D2184: after logout, the access token a refresh superseded is refused at once, and so is the chain")
    void logoutRevokesTheWholeSession() throws Exception {
        User nurse = withPassword(accounts.userAt("nurse", hospitalId, "NURSE"));

        JsonNode signIn = json(login(nurse));
        String access1 = signIn.get("accessToken").asString();
        String refresh1 = signIn.get("refreshToken").asString();
        JsonNode other = json(login(nurse));                // the same user on another device
        String otherAccess = other.get("accessToken").asString();

        JsonNode rotated = json(refresh(refresh1));
        String access2 = rotated.get("accessToken").asString();
        String refresh2 = rotated.get("refreshToken").asString();
        assertThat(jwtTokenProvider.getSessionId(access2)).isEqualTo(jwtTokenProvider.getSessionId(access1));
        assertThat(status(as(access1, get("/auth/session/bootstrap")))).isEqualTo(200);

        // Logout with the CURRENT pair only, as the portal does.
        assertThat(mockMvc.perform(post("/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access2)
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh2 + "\"}"))
            .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(status(as(access2, get("/auth/session/bootstrap")))).as("current access").isEqualTo(401);
        assertThat(status(as(access1, get("/auth/session/bootstrap")))).as("superseded access").isEqualTo(401);
        assertThat(status(refresh(refresh2))).as("current refresh").isEqualTo(401);
        // The other device's session is not this one: it lives on.
        assertThat(status(as(otherAccess, get("/auth/session/bootstrap")))).as("other session").isEqualTo(200);
    }

    @Test
    @DisplayName("D2184: an idle client's logout (expired bearer, refresh only) still ends the session's access tokens")
    void logoutWithOnlyTheRefreshTokenRevokesTheSession() throws Exception {
        User nurse = withPassword(accounts.userAt("nurse", hospitalId, "NURSE"));
        JsonNode signIn = json(login(nurse));
        String access1 = signIn.get("accessToken").asString();
        String refresh2 = json(refresh(signIn.get("refreshToken").asString())).get("refreshToken").asString();

        assertThat(mockMvc.perform(post("/auth/logout")
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh2 + "\"}"))
            .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(status(as(access1, get("/auth/session/bootstrap")))).isEqualTo(401);
    }

    @Test
    @DisplayName("D2184: a session opened through the MFA step is revoked whole too")
    void mfaSessionIsRevokedWhole() throws Exception {
        User nurse = withPassword(accounts.userAt("nurse", hospitalId, "NURSE"));
        String secret = enrol(nurse, json(login(nurse)).get("accessToken").asString());
        JsonNode session = json(verify(json(login(nurse)).get("mfaToken").asString(), currentCode(secret)));
        String access1 = session.get("accessToken").asString();
        JsonNode rotated = json(refresh(session.get("refreshToken").asString()));

        assertThat(mockMvc.perform(post("/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rotated.get("accessToken").asString())
                .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF))
            .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(status(as(access1, get("/auth/session/bootstrap")))).isEqualTo(401);
        assertThat(status(refresh(rotated.get("refreshToken").asString()))).isEqualTo(401);
    }

    // ── A reused refresh token; the absolute session lifetime ──────────────

    @Autowired private com.example.hms.security.TokenBlacklistService tokenBlacklistService;

    /** The rotation grace of {@code refreshToken} is over, as if 30 s had passed. */
    @SuppressWarnings("unchecked")
    private void graceOver(String refreshToken) {
        Object store = org.springframework.test.util.AopTestUtils.getTargetObject(tokenBlacklistService);
        assertThat(store).isInstanceOf(com.example.hms.security.InMemoryTokenBlacklistService.class);
        ((java.util.Map<String, Long>) org.springframework.test.util.ReflectionTestUtils.getField(store, "recentlyRotated"))
            .remove(jwtTokenProvider.getJtiFromToken(refreshToken));
    }

    @Test
    @DisplayName("a refresh token used again after the grace ends the session: the newest pair is refused too")
    void reusedRefreshTokenEndsTheSession() throws Exception {
        User nurse = withPassword(accounts.userAt("nurse", hospitalId, "NURSE"));
        JsonNode signIn = json(login(nurse));
        String refresh1 = signIn.get("refreshToken").asString();
        String otherAccess = json(login(nurse)).get("accessToken").asString(); // another device

        JsonNode rotated = json(refresh(refresh1));
        String access2 = rotated.get("accessToken").asString();
        String refresh2 = rotated.get("refreshToken").asString();

        // Within the grace: another tab lost the race (409); nothing ends.
        assertThat(status(refresh(refresh1))).as("reuse within the grace").isEqualTo(409);
        assertThat(status(as(access2, get("/auth/session/bootstrap")))).isEqualTo(200);

        graceOver(refresh1);
        assertThat(status(refresh(refresh1))).as("reuse after the grace").isEqualTo(401);

        assertThat(status(refresh(refresh2))).as("the newest refresh token").isEqualTo(401);
        assertThat(status(as(access2, get("/auth/session/bootstrap")))).as("the newest access token").isEqualTo(401);
        assertThat(status(as(otherAccess, get("/auth/session/bootstrap")))).as("another session").isEqualTo(200);
    }

    @Test
    @DisplayName("a session 12 h after its sign-in is not refreshed, and the answer says why")
    void sessionPastItsLifetimeIsNotRefreshed() throws Exception {
        User nurse = withPassword(accounts.userAt("nurse", hospitalId, "NURSE"));
        JsonNode signIn = json(login(nurse));
        String sessionId = jwtTokenProvider.getSessionId(signIn.get("refreshToken").asString());
        var descriptor = new com.example.hms.security.TokenUserDescriptor(
            nurse.getId(), nurse.getUsername(), List.of("ROLE_NURSE"));
        com.example.hms.security.SessionLifetime lifetime =
            new com.example.hms.security.SessionLifetime(java.time.Duration.ofHours(12));

        java.time.Instant longAgo = java.time.Instant.now().minus(java.time.Duration.ofHours(12)).minusSeconds(5);
        String expired = jwtTokenProvider.generateRefreshToken(descriptor, null, sessionId,
            lifetime.live(longAgo, longAgo, longAgo).orElseThrow());
        MvcResult refused = refresh(expired);
        assertThat(status(refused)).isEqualTo(401);
        assertThat(refused.getResponse().getHeader("WWW-Authenticate")).contains("session_lifetime");

        java.time.Instant recently = java.time.Instant.now().minus(java.time.Duration.ofHours(11));
        String live = jwtTokenProvider.generateRefreshToken(descriptor, null, sessionId,
            lifetime.live(recently, recently, recently).orElseThrow());
        JsonNode rotated = json(refresh(live));
        assertThat(jwtTokenProvider.signedInAt(rotated.get("refreshToken").asString()))
            .as("the rotated token keeps the original sign-in time")
            .isEqualTo(recently.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Enrols and confirms TOTP with a full session; returns the secret. */
    private String enrol(User user, String accessToken) throws Exception {
        mfaUsers.add(user.getId());
        String secret = json(as(accessToken, csrf(post("/auth/mfa/enroll")))).get("secret").asString();
        json(as(accessToken, csrf(post("/auth/mfa/verify-enrollment"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + currentCode(secret) + "\"}")));
        return secret;
    }

    private static String currentCode(String secret) throws Exception {
        return new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
            .generate(secret, new SystemTimeProvider().getTime() / 30);
    }

    /** A six-digit code that is not the current one (nor, by construction, a neighbour). */
    private static String wrongCode(String secret) throws Exception {
        long step = new SystemTimeProvider().getTime() / 30;
        DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        java.util.Set<String> valid = java.util.Set.of(generator.generate(secret, step - 1),
            generator.generate(secret, step), generator.generate(secret, step + 1));
        for (int candidate = 0; ; candidate++) {
            String code = String.format("%06d", candidate);
            if (!valid.contains(code)) {
                return code;
            }
        }
    }

    private MvcResult verify(String mfaToken, String code) throws Exception {
        return mockMvc.perform(post("/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + code + "\"}"))
            .andReturn();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/auth/token/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andReturn();
    }

    private User withPassword(User user) {
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userRepository.save(user);
    }

    private MvcResult login(User user) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andReturn();
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    /**
     * Spring Security's test CSRF token, not a hand-made cookie/header pair:
     * the cookie pair passed locally and failed in a CI shard (403
     * CSRF_TOKEN_INVALID), so the test does not depend on the repository's state.
     */
    private static MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
        return request.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private JsonNode json(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
            .as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " "
                + result.getResponse().getContentAsString())
            .isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
