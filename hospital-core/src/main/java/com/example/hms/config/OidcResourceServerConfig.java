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
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

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
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefaultWithIssuer(issuerUri);
        if (!audience.isBlank()) {
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaultValidator, new AudienceValidator(audience)));
        } else {
            decoder.setJwtValidator(defaultValidator);
        }
        return decoder;
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
