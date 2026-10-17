package com.example.hms.config;

import com.example.hms.security.oidc.IssuerAwareBearerTokenResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * S-03 phase 1 — Keycloak resource-server wiring.
 *
 * <p>Activated only when {@code app.auth.oidc.issuer-uri} is set. When unset
 * (default in dev / current prod), the entire OIDC stack is absent and the
 * existing custom JWT filter remains the only authenticator.</p>
 *
 * <p>This is intentionally additive: it does not retire {@code JwtAuthenticationFilter},
 * does not remove {@code JwtTokenProvider.generateAccessToken/RefreshToken}, and does
 * not rewire {@code RoleValidator}. Those are S-03 phases 2&ndash;4 and require a
 * deployed Keycloak realm plus client migration; they will land in follow-up PRs.</p>
 */
@Configuration
@ConditionalOnExpression("'${app.auth.oidc.issuer-uri:}' != ''")
public class OidcResourceServerConfig {

    private static final Logger log = LoggerFactory.getLogger(OidcResourceServerConfig.class);

    /**
     * Discovery and JWKS fetches run on request threads (the issuer is resolved
     * lazily), so they must be bounded: a Keycloak that accepts the connection
     * and never answers must not hold a Tomcat thread, and every thread queued
     * behind the decoder's initialisation lock, indefinitely.
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    @Value("${app.auth.oidc.issuer-uri}")
    private String issuerUri;

    @Value("${app.auth.oidc.audience:}")
    private String audience;

    /**
     * JWT decoder that validates Keycloak-issued tokens against the realm's JWKS
     * endpoint (auto-discovered from the issuer URI). When {@code app.auth.oidc.audience}
     * is set, an extra {@code aud} claim validator is added to enforce that tokens
     * were minted for this resource server.
     */
    @Bean
    public JwtDecoder oidcJwtDecoder() {
        log.info("[OIDC] Configuring resource-server JWT decoder for issuer={} audience={}",
                issuerUri, audience.isBlank() ? "<none>" : audience);
        // Resolved on the first Keycloak token, not at startup: an unreachable
        // issuer (Keycloak down, 502) must not stop the application, whose
        // legacy sign-in does not need Keycloak at all. SupplierJwtDecoder
        // retries the discovery on the next token until it succeeds, then
        // keeps the decoder. The initialisation failure is re-thrown as a
        // BadJwtException: the resource server turns that one (only) into 401, not 500.
        JwtDecoder lazy = new SupplierJwtDecoder(this::buildIssuerDecoder);
        return token -> {
            try {
                return lazy.decode(token);
            } catch (JwtDecoderInitializationException issuerUnavailable) {
                log.warn("[OIDC] Issuer {} unavailable; Keycloak token refused", issuerUri);
                throw new BadJwtException("OIDC issuer unavailable", issuerUnavailable);
            }
        };
    }

    /** The issuer-discovered decoder with the issuer (and optional audience) validators. */
    JwtDecoder buildIssuerDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri)
                .restOperations(boundedRestTemplate())
                .build();

        OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
        if (!audience.isBlank()) {
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaultValidator, new AudienceValidator(audience)));
        } else {
            decoder.setJwtValidator(defaultValidator);
        }
        return decoder;
    }

    /** A RestTemplate whose connect and read are both time-bounded. */
    static RestTemplate boundedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(factory);
    }

    /**
     * Bearer-token resolver that returns the token to the resource-server filter
     * <em>only</em> when its {@code iss} claim matches our Keycloak issuer, so the
     * existing internal-token filter is unaffected for non-OIDC tokens.
     */
    @Bean
    public BearerTokenResolver issuerAwareBearerTokenResolver() {
        return new IssuerAwareBearerTokenResolver(issuerUri);
    }

    /**
     * Strict {@code aud} claim validator. Spring's built-in
     * {@code JwtValidators.createDefaultWithAudience} doesn't exist for all versions
     * we target, so we ship a small custom one.
     */
    static final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

        private static final String ERROR_CODE = "invalid_audience";
        private final String requiredAudience;

        AudienceValidator(String requiredAudience) {
            this.requiredAudience = requiredAudience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            if (token.getAudience() != null && token.getAudience().contains(requiredAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    ERROR_CODE,
                    "The required audience '" + requiredAudience + "' is missing from the token",
                    null));
        }
    }
}
