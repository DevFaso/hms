package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.enums.FacilityType;
import com.example.hms.model.User;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.TenantLifecycleGate;
import com.example.hms.security.oidc.IssuerAwareBearerTokenResolver;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.LinkedTestAccounts;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Provider confinement through the REAL security chain (provider plan §3.3,
 * §6.4, AC-8): signed Keycloak-shaped tokens for real local accounts, whose
 * live assignments place them.
 *
 * <p>A provider user, pinned to their pharmacy or not, gets the answer of an
 * unmapped path for every hospital endpoint (never a role matcher's 403), and
 * reaches the common allow-list. A provider user who is also a patient keeps
 * the patient self-service handlers, whether their PATIENT row is global or
 * bound to the hospital that registered them. Hospital users are untouched.
 */
@AutoConfigureMockMvc
@Import(ProviderConfinementSecurityIT.SignedKeycloakTokens.class)
class ProviderConfinementSecurityIT extends BaseIT {

    private static final KeyPair KEYS = rsaKeyPair();
    private static final String UNMAPPED = "/provider-confinement-no-such-endpoint";

    @Autowired private MockMvc mockMvc;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;
    @Autowired private IdleSessionTracker idleSessionTracker;

    private LinkedTestAccounts accounts;
    private UUID hospitalId;
    private UUID pharmacyId;
    private UUID laboratoryId;

    @BeforeEach
    void setUp() {
        accounts = new LinkedTestAccounts(hospitalRepository, userRepository, roleRepository,
            assignmentRepository, auditEventLogRepository);
        hospitalId = accounts.hospital("Confinement Hospital").getId();
        pharmacyId = accounts.provider("Confinement Pharmacy", FacilityType.PHARMACY).getId();
        laboratoryId = accounts.provider("Confinement Lab", FacilityType.LABORATORY).getId();
    }

    @AfterEach
    void tearDown() {
        accounts.cleanUp();
    }

    /** The hospital endpoints AC-8 names, and a few more, each answered as an unmapped path. */
    private List<MockHttpServletRequestBuilder> hospitalEndpoints() {
        UUID someId = UUID.randomUUID();
        return List.of(
            get("/patients/search").param("q", "doe"),
            get("/lab-results"),
            get("/lab-orders"),
            post("/lab-orders").contentType(MediaType.APPLICATION_JSON).content("{}"),
            put("/lab-orders/{id}", someId).contentType(MediaType.APPLICATION_JSON).content("{}"),
            delete("/lab-orders/{id}", someId),
            get("/prescriptions"),
            post("/break-glass").contentType(MediaType.APPLICATION_JSON).content("{}"),
            get("/hospitals"),
            get("/users/{id}", someId),
            get("/me/patient-flow"),
            get("/patients/{patientId}/record-access", someId));
    }

    @Test
    @DisplayName("a pharmacist pinned to their pharmacy gets the unmapped path's 404 for every hospital endpoint")
    void pinnedPharmacistIsConfined() throws Exception {
        String pharmacist = linkedToken("pharm", pharmacyId, "PHARMACIST");
        String unmapped = refusalShape(as(doctorToken(), get(UNMAPPED)));

        for (MockHttpServletRequestBuilder request : hospitalEndpoints()) {
            MvcResult result = as(pharmacist, request);
            assertThat(refusalShape(result)).as(label(result)).isEqualTo(unmapped);
        }
    }

    @Test
    @DisplayName("an UNPINNED provider user (a pharmacy and a hospital-bound PATIENT row) is confined too")
    void unpinnedProviderIsConfined() throws Exception {
        User user = accounts.userAt("pharmpat", pharmacyId, "PHARMACIST");
        accounts.assign(user, hospitalId, "PATIENT");
        String token = tokenFor(user, "PHARMACIST", "PATIENT");
        String unmapped = refusalShape(as(doctorToken(), get(UNMAPPED)));

        for (MockHttpServletRequestBuilder request : hospitalEndpoints()) {
            MvcResult result = as(token, request);
            assertThat(refusalShape(result)).as(label(result)).isEqualTo(unmapped);
        }
        // Naming the hospital where they are a patient does not lift it.
        MvcResult named = as(token, get("/patients/search").param("q", "doe")
            .header("X-Hospital-Id", hospitalId.toString()));
        assertThat(refusalShape(named)).isEqualTo(unmapped);
    }

    @Test
    @DisplayName("a laboratory scientist reaches no lab handler in P1, and no hospital endpoint")
    void labScientistIsConfined() throws Exception {
        String scientist = linkedToken("labsci", laboratoryId, "LAB_SCIENTIST");
        String unmapped = refusalShape(as(doctorToken(), get(UNMAPPED)));

        for (MockHttpServletRequestBuilder request : hospitalEndpoints()) {
            MvcResult result = as(scientist, request);
            assertThat(refusalShape(result)).as(label(result)).isEqualTo(unmapped);
        }
    }

    @Test
    @DisplayName("the common allow-list answers normally for a provider user")
    void commonAllowListIsReachable() throws Exception {
        String pharmacist = linkedToken("pharm", pharmacyId, "PHARMACIST");
        String unmapped = refusalShape(as(doctorToken(), get(UNMAPPED)));

        for (MockHttpServletRequestBuilder request : List.of(
                get("/notifications"),
                get("/notifications/preferences"),
                get("/me/assignments"),
                get("/me/dashboard-config"),
                get("/feature-flags"))) {
            MvcResult result = as(pharmacist, request);
            assertThat(result.getResponse().getStatus()).as(label(result)).isEqualTo(200);
        }
        // Under the /auth wholesale prefix: the handler answers (this one wants
        // a legacy token, so 401), not the confinement.
        MvcResult auth = as(pharmacist, get("/auth/mfa/status"));
        assertThat(refusalShape(auth)).as(label(auth)).isNotEqualTo(unmapped);
        assertThat(unmapped).startsWith("404|");
    }

    @Test
    @DisplayName("GET /users/{id} reaches the handler for the provider user's OWN id only")
    void ownProfileOnly() throws Exception {
        User pharmacistAccount = accounts.userAt("pharm", pharmacyId, "PHARMACIST");
        String token = tokenFor(pharmacistAccount, "PHARMACIST");
        String unmapped = refusalShape(as(doctorToken(), get(UNMAPPED)));

        MvcResult own = as(token, get("/users/{id}", pharmacistAccount.getId()));
        assertThat(refusalShape(own)).as(label(own)).isNotEqualTo(unmapped);
        MvcResult other = as(token, get("/users/{id}", UUID.randomUUID()));
        assertThat(refusalShape(other)).as(label(other)).isEqualTo(unmapped);
    }

    @Test
    @DisplayName("a provider user who is also a patient keeps patient self-service, with a hospital-bound or a global PATIENT row")
    void providerPatientKeepsSelfService() throws Exception {
        User bound = accounts.userAt("pharmpat", pharmacyId, "PHARMACIST");
        accounts.assign(bound, hospitalId, "PATIENT");
        User global = accounts.userAt("labpat", laboratoryId, "LAB_TECHNICIAN");
        accounts.assign(global, null, "PATIENT");
        String unmapped = refusalShape(as(doctorToken(), get(UNMAPPED)));

        for (String token : List.of(tokenFor(bound, "PHARMACIST", "PATIENT"),
                                    tokenFor(global, "LAB_TECHNICIAN", "PATIENT"))) {
            for (MockHttpServletRequestBuilder request : List.of(
                    get("/me/patient/profile"),
                    get("/me/patient/appointments"),
                    get("/patients/{patientId}/record-sharing/opt-out", UUID.randomUUID()))) {
                MvcResult result = as(token, request);
                assertThat(refusalShape(result)).as("reached the handler: " + label(result))
                    .isNotEqualTo(unmapped);
            }
        }
    }

    @Test
    @DisplayName("a provider user who is NOT a patient gets the unmapped answer for patient self-service")
    void providerWithoutPatientRowHasNoSelfService() throws Exception {
        String pharmacist = linkedToken("pharm", pharmacyId, "PHARMACIST");
        String unmapped = refusalShape(as(doctorToken(), get(UNMAPPED)));

        for (MockHttpServletRequestBuilder request : List.of(
                get("/me/patient/profile"),
                get("/patients/{patientId}/record-sharing/opt-out", UUID.randomUUID()))) {
            MvcResult result = as(pharmacist, request);
            assertThat(refusalShape(result)).as(label(result)).isEqualTo(unmapped);
        }
    }

    @Test
    @DisplayName("a hospital doctor is not confined: the same endpoints reach their handlers")
    void hospitalUserIsNotConfined() throws Exception {
        String doctor = doctorToken();
        String unmapped = refusalShape(as(doctor, get(UNMAPPED)));

        for (MockHttpServletRequestBuilder request : List.of(get("/hospitals"), get("/me/assignments"))) {
            MvcResult result = as(doctor, request);
            assertThat(refusalShape(result)).as(label(result)).isNotEqualTo(unmapped);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String doctorToken() {
        return linkedToken("doc", hospitalId, "DOCTOR");
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private static String label(MvcResult result) {
        return result.getRequest().getMethod() + " " + result.getRequest().getRequestURI();
    }

    /**
     * Everything a client sees of a 404, with the request path taken out: the
     * status, the body, the error message the container would render and the
     * content type. Shared with {@code ProviderAdminSecurityIT}.
     */
    static String refusalShape(MvcResult result) throws Exception {
        String path = result.getRequest().getRequestURI();
        String bare = path.startsWith("/api") ? path.substring(4) : path;
        String message = result.getResponse().getErrorMessage();
        String normalised = message == null ? "" : message.replace(path, "<path>").replace(bare.substring(1), "<path>");
        return result.getResponse().getStatus() + "|" + result.getResponse().getContentAsString()
            + "|" + normalised + "|" + result.getResponse().getContentType();
    }

    private String linkedToken(String username, UUID at, String... roles) {
        User user = accounts.userAt(username, at, roles);
        return tokenFor(user, roles);
    }

    private String tokenFor(User user, String... realmRoles) {
        idleSessionTracker.touch(user.getId());
        return token(user.getUsername(), user.getId(), realmRoles);
    }

    /**
     * A Keycloak-shaped token whose {@code amr} says a password and an OTP
     * were used: the provider MFA gate (AC-13) admits it, so these tests see
     * the confinement itself. {@code ProviderMfaGateIT} covers the gate.
     */
    static String token(String username, UUID appUserId, String... realmRoles) {
        return token(username, appUserId, List.of("pwd", "otp"), realmRoles);
    }

    /** As above, with the given {@code amr} claim ({@code null}: no claim at all). */
    static String token(String username, UUID appUserId, List<String> amr, String... realmRoles) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issuer(OidcResourceServerIntegrationTest.TEST_ISSUER)
            .subject(UUID.randomUUID().toString())
            .audience(List.of(OidcResourceServerIntegrationTest.TEST_AUDIENCE))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("preferred_username", username)
            .claim("typ", "Bearer")
            .claim("azp", "hms-portal")
            .claim("appUserId", appUserId.toString())
            .claim("realm_access", Map.of("roles", List.of(realmRoles)));
        if (amr != null) {
            builder.claim("amr", amr);
        }
        JWTClaimsSet claims = builder.build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("confinement-test").build(), claims);
        try {
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
        return jwt.serialize();
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The decoder, resolver and hospital-context filter the other signed-token ITs wire, over this class's key. */
    @TestConfiguration
    static class SignedKeycloakTokens {

        @Bean
        @Primary
        JwtDecoder oidcJwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(OidcResourceServerIntegrationTest.TEST_ISSUER),
                new OidcResourceServerIntegrationTest.TestAudienceValidator(OidcResourceServerIntegrationTest.TEST_AUDIENCE));
            decoder.setJwtValidator(validators);
            return decoder;
        }

        @Bean
        @Primary
        BearerTokenResolver issuerAwareBearerTokenResolver() {
            return new IssuerAwareBearerTokenResolver(OidcResourceServerIntegrationTest.TEST_ISSUER);
        }

        @Bean
        KeycloakHospitalContextFilter keycloakHospitalContextFilter(KeycloakHospitalContextResolver resolver,
                                                                   ActingScopeResolver actingScopeResolver,
                                                                   IdleSessionGate idleSessionGate,
                                                                   TenantLifecycleGate tenantLifecycleGate,
                                                                   UserRepository userRepository) {
            return new KeycloakHospitalContextFilter(resolver, actingScopeResolver, idleSessionGate,
                tenantLifecycleGate, userRepository);
        }
    }
}
