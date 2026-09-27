package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.model.Consultation;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.UltrasoundOrder;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UltrasoundOrderRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.oidc.IssuerAwareBearerTokenResolver;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The reads by patient id through the REAL security filter chain, with a real
 * signed Keycloak-style bearer token that carries the HMS user id in
 * {@code appUserId} — decoded by the resource server, converted by
 * {@code KeycloakJwtAuthenticationConverter}, run past
 * {@code KeycloakHospitalContextFilter}, the URL matchers and the method
 * security, exactly as a mobile-app request is.
 *
 * <p>A slice test or a hand-built {@code JwtAuthenticationToken} skips the
 * piece that decides who the caller is on this path; this one does not. The
 * token's subject is deliberately NOT the user id, so a guard that fell back
 * to {@code sub} would refuse the patient their own record here.
 *
 * <p>Repositories are mocked: what is under test is which ids reach them.
 */
@AutoConfigureMockMvc
@Import(PatientSubjectReadSecurityIT.SignedKeycloakTokens.class)
class PatientSubjectReadSecurityIT extends BaseIT {

    private static final KeyPair KEYS = rsaKeyPair();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private PatientRepository patientRepository;
    @MockitoBean private UltrasoundOrderRepository ultrasoundOrderRepository;
    @MockitoBean private ConsultationRepository consultationRepository;

    private final UUID patientUserId = UUID.randomUUID();
    private final UUID ownPatientId = UUID.randomUUID();
    private final UUID otherPatientId = UUID.randomUUID();
    private final UUID unknownPatientId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void linkThePatientAccountToItsRow() {
        when(patientRepository.existsByIdAndUserId(ownPatientId, patientUserId)).thenReturn(true);
    }

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();

    private UltrasoundOrder ultrasoundOrderOf(UUID patientId) {
        return ultrasoundOrderOf(patientId, hospitalA);
    }

    private UltrasoundOrder ultrasoundOrderOf(UUID patientId, UUID hospitalId) {
        Patient subject = new Patient();
        subject.setId(patientId);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        UltrasoundOrder order = new UltrasoundOrder();
        order.setId(orderId);
        order.setPatient(subject);
        order.setHospital(hospital);
        return order;
    }

    private MvcResult getAs(String token, String path, Object... vars) throws Exception {
        return mockMvc.perform(get(path, vars).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    /** Status, message and path of an error body — everything but its timestamp. */
    private String errorShape(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return result.getResponse().getStatus() + "|" + body.path("message").asText() + "|" + body.path("path").asText();
    }

    @Test
    @DisplayName("a Keycloak patient reads their own ultrasound order and list, resolved from appUserId")
    void patientReadsOwn() throws Exception {
        String patient = token("patient001", patientUserId, "PATIENT");
        when(ultrasoundOrderRepository.findById(orderId)).thenReturn(Optional.of(ultrasoundOrderOf(ownPatientId)));
        when(ultrasoundOrderRepository.findAllByPatientId(ownPatientId)).thenReturn(List.of(ultrasoundOrderOf(ownPatientId)));

        MvcResult byId = getAs(patient, "/ultrasound/orders/{id}", orderId);
        assertThat(byId.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(byId.getResponse().getContentAsString()).path("patientId").asText())
            .isEqualTo(ownPatientId.toString());

        MvcResult list = getAs(patient, "/ultrasound/orders/patient/{pid}", ownPatientId);
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(list.getResponse().getContentAsString())).hasSize(1);
    }

    @Test
    @DisplayName("another patient's ultrasound order answers exactly as a missing one")
    void foreignOrderAnswersAsMissing() throws Exception {
        String patient = token("patient001", patientUserId, "PATIENT");
        when(ultrasoundOrderRepository.findById(orderId)).thenReturn(Optional.of(ultrasoundOrderOf(otherPatientId)));
        MvcResult foreign = getAs(patient, "/ultrasound/orders/{id}", orderId);

        when(ultrasoundOrderRepository.findById(orderId)).thenReturn(Optional.empty());
        MvcResult missing = getAs(patient, "/ultrasound/orders/{id}", orderId);

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorShape(foreign)).isEqualTo(errorShape(missing));
    }

    @Test
    @DisplayName("another patient's lists answer exactly as an unknown patient's, and the rows are never asked for")
    void foreignListsAnswerAsUnknown() throws Exception {
        String patient = token("patient001", patientUserId, "PATIENT");
        when(ultrasoundOrderRepository.findAllByPatientId(otherPatientId)).thenReturn(List.of(ultrasoundOrderOf(otherPatientId)));
        when(consultationRepository.findByPatient_IdOrderByRequestedAtDesc(otherPatientId)).thenReturn(List.of(new Consultation()));

        for (String path : List.of("/ultrasound/orders/patient/{pid}", "/consultations/patient/{pid}")) {
            MvcResult foreign = getAs(patient, path, otherPatientId);
            MvcResult unknown = getAs(patient, path, unknownPatientId);
            assertThat(foreign.getResponse().getStatus()).as(path).isEqualTo(200);
            assertThat(foreign.getResponse().getContentAsString()).as(path)
                .isEqualTo(unknown.getResponse().getContentAsString())
                .isEqualTo("[]");
        }
        verify(ultrasoundOrderRepository, never()).findAllByPatientId(otherPatientId);
        verify(consultationRepository, never()).findByPatient_IdOrderByRequestedAtDesc(any());
        verify(consultationRepository, never()).findByPatient_IdAndHospital_IdInOrderByRequestedAtDesc(any(), any());
    }

    @Test
    @DisplayName("a Keycloak doctor at hospital A still reads another patient's ultrasound order and list there")
    void staffUnchanged() throws Exception {
        String doctor = token("doctor001", UUID.randomUUID(), hospitalA, "DOCTOR");
        when(ultrasoundOrderRepository.findById(orderId)).thenReturn(Optional.of(ultrasoundOrderOf(otherPatientId)));
        // Acting at hospital A, the list reads the readable hospitals at the database.
        when(ultrasoundOrderRepository.findByPatient_IdAndHospital_IdInOrderByOrderedDateDesc(eq(otherPatientId), any()))
            .thenReturn(List.of(ultrasoundOrderOf(otherPatientId)));

        assertThat(getAs(doctor, "/ultrasound/orders/{id}", orderId).getResponse().getStatus()).isEqualTo(200);
        MvcResult list = getAs(doctor, "/ultrasound/orders/patient/{pid}", otherPatientId);
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(list.getResponse().getContentAsString())).hasSize(1);
    }

    @Test
    @DisplayName("a Keycloak doctor at hospital A is refused B's ultrasound order by id, exactly as a missing one")
    void staffAtAnotherHospitalAnswersAsMissing() throws Exception {
        String doctor = token("doctor001", UUID.randomUUID(), hospitalA, "DOCTOR");
        when(ultrasoundOrderRepository.findById(orderId))
            .thenReturn(Optional.of(ultrasoundOrderOf(otherPatientId, hospitalB)));
        MvcResult foreign = getAs(doctor, "/ultrasound/orders/{id}", orderId);

        when(ultrasoundOrderRepository.findById(orderId)).thenReturn(Optional.empty());
        MvcResult missing = getAs(doctor, "/ultrasound/orders/{id}", orderId);

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorShape(foreign)).isEqualTo(errorShape(missing));
    }

    // ── token minting ──────────────────────────────────────────────────────

    /**
     * A token shaped as {@code keycloak/realm-export.json} issues one: realm
     * roles under {@code realm_access}, the HMS user id in {@code appUserId},
     * and a Keycloak subject that is NOT the user id.
     */
    private static String token(String username, UUID appUserId, String... realmRoles) {
        return token(username, appUserId, null, realmRoles);
    }

    /** With {@code hospital_id}, the claim KeycloakHospitalContextResolver makes the active hospital. */
    private static String token(String username, UUID appUserId, UUID hospitalId, String... realmRoles) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
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
        if (hospitalId != null) {
            claims.claim("hospital_id", hospitalId.toString());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("b2own-test").build(), claims.build());
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

    /**
     * The same stand-in for the discovery-built production decoder that
     * {@code OidcResourceServerIntegrationTest.OidcTestConfig} uses — same
     * issuer and audience validators — over a key this class can sign with,
     * since that fixture's token spec has no {@code appUserId} claim.
     */
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

        /**
         * Production registers this filter only when an issuer URI is
         * configured, which the test profile does not do — so without it a
         * Keycloak principal would never get the hospital its
         * {@code hospital_id} claim names, and every staff read would stop at
         * "select a hospital". The same class, wired exactly as SecurityConfig
         * wires it (after the bearer-token filter).
         */
        @Bean
        KeycloakHospitalContextFilter keycloakHospitalContextFilter(KeycloakHospitalContextResolver resolver,
                                                                   IdleSessionGate idleSessionGate,
                                                                   UserRepository userRepository) {
            return new KeycloakHospitalContextFilter(resolver, idleSessionGate, userRepository);
        }
    }
}
