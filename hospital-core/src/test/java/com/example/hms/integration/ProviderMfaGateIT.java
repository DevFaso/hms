package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.enums.FacilityType;
import com.example.hms.model.User;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MfaBackupCodeRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserMfaEnrollmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.TokenUserDescriptor;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Provider MFA (provider plan AC-13, T4) through the REAL security chain, on
 * both auth paths.
 *
 * <p>A user with a live assignment at a pharmacy or laboratory whose token
 * carries no second factor (Keycloak: no {@code otp} in {@code amr}; legacy:
 * a password-only token) gets {@code mfaEnrollmentRequired} at the session
 * bootstrap and 403 {@code mfa.enrollment.required} for every other request
 * outside sign-in and MFA enrolment, the same for an allowed, a refused and an
 * unmapped path. With the second factor the confinement decides as before. A
 * hospital user is never asked. The legacy login challenges a provider user
 * whatever the role list says, and a refresh keeps the proof it was given.
 */
@AutoConfigureMockMvc
@Import(ProviderConfinementSecurityIT.SignedKeycloakTokens.class)
class ProviderMfaGateIT extends BaseIT {

    private static final String UNMAPPED = "/provider-mfa-no-such-endpoint";
    private static final String CODE = "mfa.enrollment.required";
    private static final String CSRF = "provider-mfa-csrf";
    private static final List<String> PASSWORD_ONLY = List.of("pwd");
    private static final List<String> PASSWORD_AND_OTP = List.of("pwd", "otp");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;
    @Autowired private IdleSessionTracker idleSessionTracker;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserMfaEnrollmentRepository enrollmentRepository;
    @Autowired private MfaBackupCodeRepository backupCodeRepository;

    /** Accounts that enrolled TOTP: their enrolment and backup codes go before the account. */
    private final List<UUID> mfaUsers = new java.util.ArrayList<>();

    private LinkedTestAccounts accounts;
    private UUID hospitalId;
    private UUID pharmacyId;
    private UUID laboratoryId;

    @BeforeEach
    void setUp() {
        accounts = new LinkedTestAccounts(hospitalRepository, userRepository, roleRepository,
            assignmentRepository, auditEventLogRepository);
        hospitalId = accounts.hospital("MFA Hospital").getId();
        pharmacyId = accounts.provider("MFA Pharmacy", FacilityType.PHARMACY).getId();
        laboratoryId = accounts.provider("MFA Lab", FacilityType.LABORATORY).getId();
    }

    @AfterEach
    void tearDown() {
        for (UUID userId : mfaUsers) {
            backupCodeRepository.deleteAll(backupCodeRepository.findByUserIdAndUsedFalse(userId));
            enrollmentRepository.deleteAll(enrollmentRepository.findByUserId(userId));
        }
        mfaUsers.clear();
        accounts.cleanUp();
    }

    // ── Keycloak ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Keycloak, no otp in amr: mfaEnrollmentRequired at bootstrap, the same 403 everywhere else")
    void keycloakWithoutOtp() throws Exception {
        for (List<String> amr : java.util.Arrays.asList(PASSWORD_ONLY, null)) {
            User pharmacist = accounts.userAt("pharm", pharmacyId, "PHARMACIST");
            String token = keycloak(pharmacist, amr, "PHARMACIST");

            JsonNode session = json(as(token, get("/auth/session/bootstrap")));
            assertThat(session.get("providerUser").asBoolean()).isTrue();
            assertThat(session.get("mfaEnrollmentRequired").asBoolean()).isTrue();

            for (MockHttpServletRequestBuilder request : List.of(
                    get("/notifications"),                  // on the provider allow-list
                    get("/me/assignments"),                 // on it too
                    get("/patients/search").param("q", "x"), // a hospital endpoint
                    get(UNMAPPED),                          // no handler
                    csrf(post("/auth/ws-ticket")))) {       // under /auth, but not sign-in
                assertMfaRefusal(as(token, request));
            }
        }
    }

    @Test
    @DisplayName("Keycloak, otp in amr: no flag, and the confinement decides as before")
    void keycloakWithOtp() throws Exception {
        User pharmacist = accounts.userAt("pharm", pharmacyId, "PHARMACIST");
        String token = keycloak(pharmacist, PASSWORD_AND_OTP, "PHARMACIST");
        String unmapped = ProviderConfinementSecurityIT.refusalShape(
            as(keycloak(accounts.userAt("doc", hospitalId, "DOCTOR"), null, "DOCTOR"), get(UNMAPPED)));

        assertThat(json(as(token, get("/auth/session/bootstrap"))).get("mfaEnrollmentRequired").asBoolean())
            .isFalse();
        assertThat(as(token, get("/notifications")).getResponse().getStatus()).isEqualTo(200);
        assertThat(as(token, csrf(post("/auth/ws-ticket"))).getResponse().getStatus()).isEqualTo(200);
        assertThat(ProviderConfinementSecurityIT.refusalShape(as(token, get("/patients/search").param("q", "x"))))
            .isEqualTo(unmapped);
    }

    @Test
    @DisplayName("Keycloak: a laboratory scientist is asked too, though no role list names LAB_SCIENTIST")
    void labScientistIsAsked() throws Exception {
        String token = keycloak(accounts.userAt("labsci", laboratoryId, "LAB_SCIENTIST"), PASSWORD_ONLY,
            "LAB_SCIENTIST");

        assertThat(json(as(token, get("/auth/session/bootstrap"))).get("mfaEnrollmentRequired").asBoolean())
            .isTrue();
        assertMfaRefusal(as(token, get("/notifications")));
    }

    @Test
    @DisplayName("the refusal speaks the request's language")
    void refusalIsLocalised() throws Exception {
        String token = keycloak(accounts.userAt("pharm", pharmacyId, "PHARMACIST"), PASSWORD_ONLY, "PHARMACIST");

        JsonNode french = json403(as(token, get("/notifications").header(HttpHeaders.ACCEPT_LANGUAGE, "fr")));
        JsonNode english = json403(as(token, get("/notifications").header(HttpHeaders.ACCEPT_LANGUAGE, "en")));

        assertThat(french.get("message").asString()).startsWith("La vérification en deux étapes");
        assertThat(english.get("message").asString()).startsWith("Two-step verification");
    }

    @Test
    @DisplayName("a hospital user is never asked, with or without otp")
    void hospitalUserIsNeverAsked() throws Exception {
        String doctor = keycloak(accounts.userAt("doc", hospitalId, "DOCTOR"), PASSWORD_ONLY, "DOCTOR");

        JsonNode session = json(as(doctor, get("/auth/session/bootstrap")));
        assertThat(session.get("providerUser").asBoolean()).isFalse();
        assertThat(session.get("mfaEnrollmentRequired").asBoolean()).isFalse();
        assertThat(as(doctor, get("/me/assignments")).getResponse().getStatus()).isEqualTo(200);
    }

    // ── Legacy ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("legacy: a password-only token is refused, the token minted after the TOTP challenge is not")
    void legacyTokens() throws Exception {
        User pharmacist = accounts.userAt("pharm", pharmacyId, "PHARMACIST");
        idleSessionTracker.touch(pharmacist.getId());
        TokenUserDescriptor descriptor = new TokenUserDescriptor(pharmacist.getId(), pharmacist.getUsername(),
            List.of("ROLE_PHARMACIST"));
        String passwordOnly = jwtTokenProvider.generateAccessToken(descriptor);
        String afterTotp = jwtTokenProvider.generateAccessToken(descriptor, true);

        assertThat(json(as(passwordOnly, get("/auth/session/bootstrap"))).get("mfaEnrollmentRequired").asBoolean())
            .isTrue();
        assertMfaRefusal(as(passwordOnly, get("/notifications")));
        // MFA enrolment stays reachable (the handler answers, not the gate).
        assertThat(as(passwordOnly, get("/auth/mfa/status")).getResponse().getStatus()).isEqualTo(200);

        assertThat(json(as(afterTotp, get("/auth/session/bootstrap"))).get("mfaEnrollmentRequired").asBoolean())
            .isFalse();
        assertThat(as(afterTotp, get("/notifications")).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("legacy login: a provider user is challenged whatever the role list says; a hospital user is not")
    void legacyLoginChallengesProviderUsers() throws Exception {
        User atLab = withPassword(accounts.userAt("labsci", laboratoryId, "LAB_SCIENTIST"));
        User atHospital = withPassword(accounts.userAt("hlabsci", hospitalId, "LAB_SCIENTIST"));

        JsonNode provider = json(login(atLab));
        assertThat(provider.get("mfaRequired").asBoolean()).isTrue();
        assertThat(provider.path("accessToken").isMissingNode() || provider.get("accessToken").isNull()).isTrue();

        JsonNode hospital = json(login(atHospital));
        assertThat(hospital.path("mfaRequired").asBoolean(false)).isFalse();
        assertThat(hospital.get("accessToken").asString()).isNotBlank();
    }

    @Test
    @DisplayName("legacy, end to end: login, enrol with the challenge token, verify, and only then act")
    void legacyEnrolmentEndToEnd() throws Exception {
        User labScientist = withPassword(accounts.userAt("labsci", laboratoryId, "LAB_SCIENTIST"));
        mfaUsers.add(labScientist.getId());

        JsonNode challenge = json(login(labScientist));
        assertThat(challenge.get("mfaRequired").asBoolean()).isTrue();
        assertThat(challenge.get("mfaEnrolled").asBoolean()).isFalse();
        String mfaToken = challenge.get("mfaToken").asString();
        // The challenge does not open the idle window; the portal's next calls would.
        idleSessionTracker.touch(labScientist.getId());
        // The challenge token is password-only: enrolment, nothing else.
        assertMfaRefusal(as(mfaToken, get("/notifications")));

        String secret = json(as(mfaToken, csrf(post("/auth/mfa/enroll")))).get("secret").asString();
        String code = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
            .generate(secret, new SystemTimeProvider().getTime() / 30);
        json(as(mfaToken, csrf(post("/auth/mfa/verify-enrollment"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}")));
        String accessToken = json(mockMvc.perform(post("/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + code + "\"}"))
            .andReturn()).get("accessToken").asString();

        assertThat(json(as(accessToken, get("/auth/session/bootstrap"))).get("mfaEnrollmentRequired").asBoolean())
            .isFalse();
        assertThat(as(accessToken, get("/notifications")).getResponse().getStatus()).isEqualTo(200);

        // The password alone cannot swap the authenticator now: the next
        // challenge token is refused a re-enrolment...
        String nextChallenge = json(login(labScientist)).get("mfaToken").asString();
        assertMfaRefusal(as(nextChallenge, csrf(post("/auth/mfa/enroll"))));
        // ...and the authenticator it would have reset still answers the challenge.
        String nextCode = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
            .generate(secret, new SystemTimeProvider().getTime() / 30);
        String withFactor = json(mockMvc.perform(post("/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mfaToken\":\"" + nextChallenge + "\",\"code\":\"" + nextCode + "\"}"))
            .andReturn()).get("accessToken").asString();
        // With the factor, replacing it (a new phone) is allowed.
        assertThat(as(withFactor, csrf(post("/auth/mfa/enroll"))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("without the factor a provider user cannot change the account itself, only sign in and enrol")
    void accountChangesNeedTheFactor() throws Exception {
        User pharmacist = accounts.userAt("pharm", pharmacyId, "PHARMACIST");
        idleSessionTracker.touch(pharmacist.getId());
        String passwordOnly = jwtTokenProvider.generateAccessToken(new TokenUserDescriptor(pharmacist.getId(),
            pharmacist.getUsername(), List.of("ROLE_PHARMACIST")));

        for (MockHttpServletRequestBuilder request : List.of(
                csrf(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/auth/credentials/mfa"))
                    .contentType(MediaType.APPLICATION_JSON).content("[]"),
                csrf(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/auth/credentials/recovery"))
                    .contentType(MediaType.APPLICATION_JSON).content("[]"),
                get("/auth/credentials/me"),
                csrf(post("/auth/me/change-email")).contentType(MediaType.APPLICATION_JSON).content("{}"),
                csrf(post("/auth/me/change-username")).contentType(MediaType.APPLICATION_JSON).content("{}"))) {
            assertMfaRefusal(as(passwordOnly, request));
        }
        assertThat(as(passwordOnly, get("/auth/mfa/status")).getResponse().getStatus()).isEqualTo(200);
        assertThat(as(passwordOnly, get("/auth/csrf-token")).getResponse().getStatus()).isBetween(200, 299);
    }

    @Test
    @DisplayName("a hospital user with an authenticator re-enrols as before, the factor or not")
    void hospitalReEnrolmentUnchanged() throws Exception {
        User doctor = accounts.userAt("doc", hospitalId, "DOCTOR");
        mfaUsers.add(doctor.getId());
        idleSessionTracker.touch(doctor.getId());
        String passwordOnly = jwtTokenProvider.generateAccessToken(new TokenUserDescriptor(doctor.getId(),
            doctor.getUsername(), List.of("ROLE_DOCTOR")));

        String secret = json(as(passwordOnly, csrf(post("/auth/mfa/enroll")))).get("secret").asString();
        String code = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
            .generate(secret, new SystemTimeProvider().getTime() / 30);
        json(as(passwordOnly, csrf(post("/auth/mfa/verify-enrollment"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}")));

        assertThat(as(passwordOnly, csrf(post("/auth/mfa/enroll"))).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("legacy refresh: the new tokens keep the second factor the refresh token carried, and never gain one")
    void legacyRefreshCarriesTheProof() throws Exception {
        User pharmacist = accounts.userAt("pharm", pharmacyId, "PHARMACIST");
        TokenUserDescriptor descriptor = new TokenUserDescriptor(pharmacist.getId(), pharmacist.getUsername(),
            List.of("ROLE_PHARMACIST"));

        for (boolean secondFactor : new boolean[] {true, false}) {
            idleSessionTracker.touch(pharmacist.getId());
            String refresh = jwtTokenProvider.generateRefreshToken(descriptor, secondFactor);
            JsonNode rotated = json(mockMvc.perform(post("/auth/token/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andReturn());

            assertThat(jwtTokenProvider.hasSecondFactor(rotated.get("accessToken").asString()))
                .as("access token, secondFactor=" + secondFactor).isEqualTo(secondFactor);
            assertThat(jwtTokenProvider.hasSecondFactor(rotated.get("refreshToken").asString()))
                .as("refresh token, secondFactor=" + secondFactor).isEqualTo(secondFactor);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void assertMfaRefusal(MvcResult result) throws Exception {
        JsonNode body = json403(result);
        assertThat(body.get("code").asString()).as(label(result)).isEqualTo(CODE);
    }

    private JsonNode json403(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(label(result) + " " + result.getResponse().getContentAsString())
            .isEqualTo(403);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode json(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(label(result) + " " + result.getResponse().getContentAsString())
            .isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String keycloak(User user, List<String> amr, String... realmRoles) {
        idleSessionTracker.touch(user.getId());
        return ProviderConfinementSecurityIT.token(user.getUsername(), user.getId(), amr, realmRoles);
    }

    private User withPassword(User user) {
        user.setPasswordHash(passwordEncoder.encode("Provider#Mfa2026"));
        return userRepository.save(user);
    }

    private MvcResult login(User user) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"Provider#Mfa2026\"}"))
            .andReturn();
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private static MockHttpServletRequestBuilder csrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    private static String label(MvcResult result) {
        return result.getRequest().getMethod() + " " + result.getRequest().getRequestURI();
    }
}
