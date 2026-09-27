package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.oidc.IssuerAwareBearerTokenResolver;
import com.example.hms.security.oidc.KeycloakHospitalContextFilter;
import com.example.hms.security.oidc.KeycloakHospitalContextResolver;
import com.example.hms.repository.UserRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * {@code /patients-v2} was an unguarded CRUD surface over the orphan
 * {@code patients_v2} table: no {@code @PreAuthorize}, no URL matcher beyond
 * {@code anyRequest().authenticated()}, no hospital column to scope by — so a
 * patient's bearer token could list, search, create and rewrite every row.
 * Nothing in the portal, the apps or the backend ever called it, and it was
 * removed rather than gated.
 *
 * <p>This drives every verb it used to serve through the REAL security chain
 * with signed Keycloak-style tokens (a patient, a doctor at another hospital,
 * and a super admin) and requires the same not-found answer from each, for a
 * listing, a search, a create and a named id alike. A re-added controller,
 * whatever its guard, fails {@link #noHandlerIsMappedUnderPatientsV2()}.
 */
@AutoConfigureMockMvc
@Import(PatientsV2RemovedSecurityIT.SignedKeycloakTokens.class)
class PatientsV2RemovedSecurityIT extends BaseIT {

    private static final KeyPair KEYS = rsaKeyPair();
    private static final String BODY = """
        {"mrn":"MRN-X","firstName":"A","lastName":"B","dateOfBirth":"1990-01-10","gender":"Male"}
        """;

    @Autowired private MockMvc mockMvc;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final UUID hospitalB = UUID.randomUUID();

    private List<MockHttpServletRequestBuilder> everyVerb(UUID id) {
        return List.of(
            get("/patients-v2"),
            get("/patients-v2").param("q", "doe"),
            get("/patients-v2/{id}", id),
            post("/patients-v2").contentType(MediaType.APPLICATION_JSON).content(BODY),
            put("/patients-v2/{id}", id).contentType(MediaType.APPLICATION_JSON).content(BODY));
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    @Test
    @DisplayName("no request mapping is registered under /patients-v2")
    void noHandlerIsMappedUnderPatientsV2() {
        List<String> mapped = handlerMapping.getHandlerMethods().keySet().stream()
            .flatMap(info -> info.getPatternValues().stream())
            .filter(pattern -> pattern.startsWith("/patients-v2"))
            .toList();
        assertThat(mapped).isEmpty();
    }

    @Test
    @DisplayName("a patient, a doctor at another hospital and a super admin all get 404 on every verb, whichever id is named")
    void everyCallerIsRefusedEveryVerb() throws Exception {
        List<String> tokens = List.of(
            token("patient001", UUID.randomUUID(), null, "PATIENT"),
            token("doctor001", UUID.randomUUID(), hospitalB, "DOCTOR"),
            token("superadmin", UUID.randomUUID(), null, "SUPER_ADMIN"));
        UUID someId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();

        for (String token : tokens) {
            List<MockHttpServletRequestBuilder> first = everyVerb(someId);
            List<MockHttpServletRequestBuilder> second = everyVerb(otherId);
            for (int i = 0; i < first.size(); i++) {
                MvcResult a = as(token, first.get(i));
                MvcResult b = as(token, second.get(i));
                String label = a.getRequest().getMethod() + " " + a.getRequest().getRequestURI();
                assertThat(a.getResponse().getStatus()).as(label).isEqualTo(404);
                assertThat(b.getResponse().getStatus()).as(label).isEqualTo(404);
                assertThat(a.getResponse().getContentAsString()).as(label)
                    .doesNotContain("mrn").doesNotContain("MRN");
            }
        }
    }

    // ── token minting (as PatientSubjectReadSecurityIT) ────────────────────

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
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("b3pv2-test").build(), claims.build());
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

    /** The decoder, resolver and hospital-context filter PatientSubjectReadSecurityIT wires, over this class's key. */
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
                                                                   IdleSessionGate idleSessionGate,
                                                                   UserRepository userRepository) {
            return new KeycloakHospitalContextFilter(resolver, idleSessionGate, userRepository);
        }
    }
}
