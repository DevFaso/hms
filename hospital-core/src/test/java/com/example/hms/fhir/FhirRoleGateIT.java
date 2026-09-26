package com.example.hms.fhir;

import com.example.hms.BaseIT;
import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.security.oidc.IssuerAwareBearerTokenResolver;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.security.oidc.KeycloakJwtFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Who may use the FHIR servlet at all. Real bearer tokens through the REAL
 * security filter chain, on both authentication paths:
 * <ul>
 *   <li>an HMS-minted JWT ({@code JwtTokenProvider}), read back by
 *       {@code JwtAuthenticationFilter}, which applies {@code RoleExpansion};</li>
 *   <li>a Keycloak-shaped RS256 JWT, decoded by the resource server and mapped
 *       by the real {@code KeycloakJwtAuthenticationConverter}, which does not.</li>
 * </ul>
 * Only the authorization decision is asserted: 401/403 means the chain
 * refused; anything else means the request got past it (MockMvc has no HAPI
 * servlet, so an admitted call ends in the dispatcher's 404).
 */
@AutoConfigureMockMvc
@Import(FhirRoleGateIT.OidcTestConfig.class)
// Its own context (the OIDC stand-ins): closed after the class so the cached
// contexts do not exhaust the 2 GB test fork (OutOfMemoryError in a later
// class of the same fork otherwise).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FhirRoleGateIT extends BaseIT {

    static final String TEST_ISSUER = "https://fhir-role-gate-it.local/realms/hms";
    private static final String ENCOUNTER = "/fhir/Encounter/" + UUID.randomUUID();
    private static final String ENCOUNTER_SEARCH = "/fhir/Encounter";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private IdleSessionTracker idleSessionTracker;
    @Autowired private UserRepository userRepository;
    @Autowired private KeycloakJwtFixture keycloak;

    private User user;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = userRepository.save(User.builder()
            .username("fhir-gate-" + suffix)
            .passwordHash("hashed-password")
            .email("fhir-gate-" + suffix + "@gate.test")
            .firstName("Gate")
            .lastName("User")
            .phoneNumber(String.format("+22670%06d", SEQUENCE.incrementAndGet()) + suffix.substring(0, 2))
            .isActive(true)
            .build());
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAllByIdInBatch(List.of(user.getId()));
    }

    /** An HMS-minted token: the roles ride in the token, as they do after a password login. */
    private String hms(String role) {
        // A login touches the idle tracker; a token minted here must too, or
        // the idle gate answers 401 before authorization is decided.
        idleSessionTracker.touch(user.getId());
        return jwtTokenProvider.generateAccessToken(
            new TokenUserDescriptor(user.getId(), user.getUsername(), List.of(role)));
    }

    /** A Keycloak token carrying the role as a realm role, as the realm export defines them. */
    private String keycloak(String role) {
        return keycloak.mintToken(KeycloakJwtFixture.TokenSpec
            .defaults(TEST_ISSUER, OidcTestConfig.AUDIENCE)
            .withRealmRoles(List.of(role)));
    }

    private int status(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("a patient token is refused on Encounter read and search, on both auth paths")
    void patientTokenIsRefused() throws Exception {
        String patient = UUID.randomUUID().toString();
        for (String token : new String[] {hms("ROLE_PATIENT"), keycloak("ROLE_PATIENT")}) {
            assertThat(status(get(ENCOUNTER), token)).isEqualTo(403);
            assertThat(status(get(ENCOUNTER_SEARCH).param("patient", patient), token)).isEqualTo(403);
            assertThat(status(post(ENCOUNTER_SEARCH + "/_search").param("patient", patient), token))
                .isEqualTo(403);
        }
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {
        "ROLE_RECEPTIONIST", "ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT", "ROLE_STAFF",
        "ROLE_LAB_SCIENTIST", "ROLE_PHARMACIST", "ROLE_HOSPITAL_ADMIN", "ROLE_FHIR_CLIENT"})
    @DisplayName("roles that do not read the chart are refused on every FHIR resource")
    void nonReadersAreRefused(String role) throws Exception {
        for (String token : new String[] {hms(role), keycloak(role)}) {
            for (String path : new String[] {ENCOUNTER, ENCOUNTER_SEARCH, "/fhir/Patient",
                "/fhir/Condition", "/fhir/MedicationRequest", "/fhir/Immunization", "/fhir"}) {
                assertThat(status(get(path), token)).as("%s on %s", role, path).isEqualTo(403);
            }
        }
    }

    @ParameterizedTest(name = "{0} reads")
    @ValueSource(strings = {
        "ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON", "ROLE_NURSE", "ROLE_MIDWIFE",
        "ROLE_RADIOLOGIST", "ROLE_ANESTHESIOLOGIST", "ROLE_PHYSIOTHERAPIST", "ROLE_SUPER_ADMIN"})
    @DisplayName("the chart readers get past the chain on both paths — named, not inherited")
    void readersAreAdmitted(String role) throws Exception {
        assertThat(status(get(ENCOUNTER), hms(role))).as(role).isNotIn(401, 403);
        // The Keycloak converter does not run RoleExpansion: the role alone must suffice.
        assertThat(status(get(ENCOUNTER), keycloak(role))).as("%s (Keycloak)", role).isNotIn(401, 403);
        assertThat(status(post(ENCOUNTER_SEARCH + "/_search"), keycloak(role))).as("%s _search", role)
            .isNotIn(401, 403);
    }

    @Test
    @DisplayName("writes admit the charting clinicians, not the consulting ones")
    void writesAreNarrowerThanReads() throws Exception {
        for (String role : new String[] {"ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON", "ROLE_NURSE",
            "ROLE_MIDWIFE", "ROLE_SUPER_ADMIN"}) {
            assertThat(status(put(ENCOUNTER), keycloak(role))).as(role).isNotIn(401, 403);
        }
        for (String role : new String[] {"ROLE_RADIOLOGIST", "ROLE_ANESTHESIOLOGIST", "ROLE_PHYSIOTHERAPIST",
            "ROLE_PATIENT", "ROLE_RECEPTIONIST"}) {
            assertThat(status(put(ENCOUNTER), keycloak(role))).as(role).isEqualTo(403);
            assertThat(status(post("/fhir/Patient"), hms(role))).as(role).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("$export admits the hospital admin its service admits, and nobody it does not")
    void bulkExportKeepsItsOwnPair() throws Exception {
        for (String path : new String[] {"/fhir/$export", "/fhir/Patient/$export"}) {
            assertThat(status(post(path), hms("ROLE_HOSPITAL_ADMIN"))).as(path).isNotIn(401, 403);
            assertThat(status(post(path), keycloak("ROLE_HOSPITAL_ADMIN"))).as(path).isNotIn(401, 403);
            assertThat(status(post(path), keycloak("ROLE_SUPER_ADMIN"))).as(path).isNotIn(401, 403);
            assertThat(status(post(path), hms("ROLE_PATIENT"))).as(path).isEqualTo(403);
            assertThat(status(post(path), keycloak("ROLE_DOCTOR"))).as(path).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("discovery stays public and everything else needs a token")
    void discoveryIsPublic() throws Exception {
        assertThat(status(get("/fhir/metadata"), null)).isNotIn(401, 403);
        assertThat(status(get("/fhir/.well-known/smart-configuration"), null)).isNotIn(401, 403);
        assertThat(status(get(ENCOUNTER), null)).isEqualTo(401);
    }

    /**
     * Stands in for {@code OidcResourceServerConfig}, which discovers its
     * decoder over HTTP: a decoder for the fixture's key, the issuer-aware
     * resolver (so HMS tokens still reach {@code JwtAuthenticationFilter}),
     * and the context filter production registers under the same property.
     */
    @TestConfiguration
    static class OidcTestConfig {

        static final String AUDIENCE = "hms-backend";

        @Bean
        KeycloakJwtFixture keycloakJwtFixture() {
            return new KeycloakJwtFixture();
        }

        @Bean
        @Primary
        JwtDecoder oidcJwtDecoder(KeycloakJwtFixture fixture) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(fixture.publicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TEST_ISSUER));
            return decoder;
        }

        @Bean
        @Primary
        BearerTokenResolver issuerAwareBearerTokenResolver() {
            return new IssuerAwareBearerTokenResolver(TEST_ISSUER);
        }

        @Bean
        KeycloakHospitalContextFilter keycloakHospitalContextFilter(KeycloakHospitalContextResolver resolver,
                                                                    IdleSessionGate idleSessionGate,
                                                                    UserRepository userRepository) {
            return new KeycloakHospitalContextFilter(resolver, idleSessionGate, userRepository);
        }
    }
}
