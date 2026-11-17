package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The issuer is resolved on the first Keycloak token, not at startup: dev was
 * down on 2026-10-16 because Keycloak answered 502 while the backend booted.
 */
class OidcResourceServerConfigLazyIssuerTest {

    /** Nothing listens on port 1: discovery fails at once. */
    private static final String UNREACHABLE_ISSUER = "http://127.0.0.1:1/realms/hms";

    private OidcResourceServerConfig config() {
        return config(UNREACHABLE_ISSUER);
    }

    private OidcResourceServerConfig config(String issuer) {
        OidcResourceServerConfig config = new OidcResourceServerConfig();
        ReflectionTestUtils.setField(config, "issuerUri", issuer);
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

    @Test
    @DisplayName("an issuer that accepts the connection and never answers is refused within the read timeout")
    void silentIssuerIsBoundedByTheReadTimeout() throws Exception {
        List<Socket> held = new ArrayList<>();
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (!silent.isClosed()) {
                        held.add(silent.accept());
                    }
                } catch (Exception closed) {
                    // the server socket closed at the end of the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            String issuer = "http://127.0.0.1:" + silent.getLocalPort() + "/realms/hms";
            JwtDecoder decoder = config(issuer).oidcJwtDecoder();
            assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                    assertThatThrownBy(() -> decoder.decode("e30.e30.sig")).isInstanceOf(BadJwtException.class));
        } finally {
            for (Socket s : held) {
                s.close();
            }
        }
    }
}
