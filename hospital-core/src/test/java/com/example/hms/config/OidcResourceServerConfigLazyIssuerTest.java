package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The issuer is resolved on the first Keycloak token, not at startup: dev was
 * down on 2026-10-16 because Keycloak answered 502 while the backend booted.
 */
class OidcResourceServerConfigLazyIssuerTest {

    /** Nothing listens on port 1: discovery fails at once. */
    private static final String UNREACHABLE_ISSUER = "http://127.0.0.1:1/realms/hms";

    private OidcResourceServerConfig config() {
        OidcResourceServerConfig config = new OidcResourceServerConfig();
        ReflectionTestUtils.setField(config, "issuerUri", UNREACHABLE_ISSUER);
        ReflectionTestUtils.setField(config, "audience", "");
        return config;
    }

    @Test
    @DisplayName("an unreachable issuer does not stop the decoder bean from being created")
    void beanIsCreatedWithoutTheIssuer() {
        assertThatCode(() -> config().oidcJwtDecoder()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a token arriving while the issuer is unreachable is refused as an authentication error")
    void decodingWithoutTheIssuerIsAJwtError() {
        JwtDecoder decoder = config().oidcJwtDecoder();
        assertThatThrownBy(() -> decoder.decode("e30.e30.sig")).isInstanceOf(BadJwtException.class);
    }
}
