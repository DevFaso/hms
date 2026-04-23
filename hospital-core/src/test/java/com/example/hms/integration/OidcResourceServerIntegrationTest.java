package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.security.oidc.IssuerAwareBearerTokenResolver;
import com.example.hms.security.oidc.KeycloakJwtFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * S-03 Phase 2.1 acceptance test — proves Keycloak-issued JWTs are accepted
 * by the resource-server filter chain, while tokens for other issuers fall
 * through to the legacy {@code JwtAuthenticationFilter} unchanged.
 *
 * <p>Uses a local RSA fixture instead of a live Keycloak so the test runs
 * offline. {@link OidcTestConfig} supplies the {@code JwtDecoder} +
 * {@code BearerTokenResolver} beans that {@code SecurityConfig} normally
 * gets from {@link OidcResourceServerConfig} when {@code OIDC_ISSUER_URI}
 * is set; we provide them directly so the production config (which does
 * an HTTP {@code .well-known/openid-configuration} lookup) never runs.</p>
 */
@AutoConfigureMockMvc
@Import(OidcResourceServerIntegrationTest.OidcTestConfig.class)
class OidcResourceServerIntegrationTest extends BaseIT {

    static final String TEST_ISSUER = "https://test-issuer.local/realms/hms";
    static final String TEST_AUDIENCE = "hms-backend";

    /**
     * Endpoint chosen for assertion: not mapped to any controller, so it falls
     * through to {@code .anyRequest().authenticated()} in {@code SecurityConfig}.
     * That gives us a clean signal — 401 means authentication failed; anything
     * else (typically 404 from Spring MVC's missing-handler dispatch) means the
     * request got past the security filter chain.
     */
    private static final String AUTHED_PROBE_PATH = "/__keycloak-it-probe__/no-such-handler";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KeycloakJwtFixture jwtFixture;

    @Test
    void keycloakIssuedJwtAuthenticatesAndReachesController() throws Exception {
        String token = jwtFixture.mintToken(
                KeycloakJwtFixture.TokenSpec.defaults(TEST_ISSUER, TEST_AUDIENCE));

        MvcResult result = mockMvc.perform(get(AUTHED_PROBE_PATH)
                        .header(AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("Valid Keycloak JWT must reach Spring MVC dispatch (401 == auth failed)")
                .isNotEqualTo(401);
        assertThat(result.getResponse().getStatus())
                .as("Valid Keycloak JWT must not be denied by AccessDecisionManager (403)")
                .isNotEqualTo(403);
    }

    @Test
    void unknownIssuerIsRoutedToLegacyFilterAndRejected() throws Exception {
        // Same fixture, but iss is something other than our configured OIDC issuer.
        // IssuerAwareBearerTokenResolver returns null -> oauth2 filter is a no-op
        // -> JwtAuthenticationFilter tries to verify with HMAC -> fails -> 401.
        String token = jwtFixture.mintToken(
                KeycloakJwtFixture.TokenSpec
                        .defaults("https://some-other-issuer.example.com/realms/x", TEST_AUDIENCE));

        mockMvc.perform(get(AUTHED_PROBE_PATH)
                        .header(AUTHORIZATION, "Bearer " + token))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("Token from an untrusted issuer must be rejected")
                        .isEqualTo(401));
    }

    @Test
    void keycloakJwtMissingRequiredAudienceIsRejected() throws Exception {
        String token = jwtFixture.mintToken(
                KeycloakJwtFixture.TokenSpec
                        .defaults(TEST_ISSUER, TEST_AUDIENCE)
                        .withAudiences(List.of("some-other-resource-server")));

        mockMvc.perform(get(AUTHED_PROBE_PATH)
                        .header(AUTHORIZATION, "Bearer " + token))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("AudienceValidator must reject tokens minted for a different audience")
                        .isEqualTo(401));
    }

    @Test
    void noBearerTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(AUTHED_PROBE_PATH))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("Anonymous request to a protected path must return 401")
                        .isEqualTo(401));
    }

    @TestConfiguration
    static class OidcTestConfig {

        @Bean
        KeycloakJwtFixture keycloakJwtFixture() {
            return new KeycloakJwtFixture();
        }

        /**
         * Stand-in for the production decoder, which is built from an HTTP discovery
         * call to {@code <issuer>/.well-known/openid-configuration}. We feed the
         * NimbusJwtDecoder our test public key directly and reuse the same default
         * issuer + audience validators that {@code OidcResourceServerConfig} composes.
         */
        @Bean
        @Primary
        JwtDecoder oidcJwtDecoder(KeycloakJwtFixture fixture) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(fixture.publicKey()).build();
            OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(TEST_ISSUER),
                    new TestAudienceValidator(TEST_AUDIENCE));
            decoder.setJwtValidator(validators);
            return decoder;
        }

        @Bean
        @Primary
        BearerTokenResolver issuerAwareBearerTokenResolver() {
            return new IssuerAwareBearerTokenResolver(TEST_ISSUER);
        }
    }

    /**
     * Test-only audience validator. Mirrors the production
     * {@code OidcResourceServerConfig.AudienceValidator} (which is package-private)
     * so the test runs without widening production visibility.
     */
    static final class TestAudienceValidator implements OAuth2TokenValidator<Jwt> {

        private final String requiredAudience;

        TestAudienceValidator(String requiredAudience) {
            this.requiredAudience = requiredAudience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            if (token.getAudience() != null && token.getAudience().contains(requiredAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_audience",
                    "The required audience '" + requiredAudience + "' is missing from the token",
                    null));
        }
    }
}
