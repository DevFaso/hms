package com.example.hms.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OidcResourceServerConfigAudienceValidatorTest {

    private static final String REQUIRED_AUDIENCE = "hms-api";

    private final OidcResourceServerConfig.AudienceValidator validator =
            new OidcResourceServerConfig.AudienceValidator(REQUIRED_AUDIENCE);

    @Test
    void succeedsWhenAudienceContainsRequiredValue() {
        Jwt token = jwt(List.of("hms-api", "another-aud"));

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void failsWhenAudienceDoesNotContainRequiredValue() {
        Jwt token = jwt(List.of("other-app"));

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> "invalid_audience".equals(e.getErrorCode()));
    }

    @Test
    void failsWhenAudienceClaimMissing() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject("u-1").build();

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isTrue();
    }

    private static Jwt jwt(List<String> audiences) {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject("u-1").audience(audiences).build();
    }
}
