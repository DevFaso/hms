package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit trail records where a request came from: the address the proxies report, not the proxy's own, and
 * nothing that is not an address. One rule for every row: no class reads the connection's address on its own.
 */
class ClientAddressTest {

    @Test
    @DisplayName("the first forwarded address wins over the proxy's own")
    void firstForwardedAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("fd12:a9af::1");
        request.addHeader("X-Forwarded-For", " 41.203.10.7 , 10.0.0.3");

        assertThat(ClientAddress.of(request)).isEqualTo("41.203.10.7");
    }

    @Test
    @DisplayName("without a forwarded address: X-Real-IP, then the connection")
    void fallsBack() {
        MockHttpServletRequest real = new MockHttpServletRequest();
        real.setRemoteAddr("10.0.0.3");
        real.addHeader("X-Real-IP", "2001:db8::7");
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("10.0.0.3");

        assertThat(ClientAddress.of(real)).isEqualTo("2001:db8::7");
        assertThat(ClientAddress.of(direct)).isEqualTo("10.0.0.3");
        assertThat(ClientAddress.of(null)).isNull();
    }

    @Test
    @DisplayName("a header that is not an address puts nothing in the row")
    void onlyAnAddressIsKept() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.3");
        request.addHeader("X-Forwarded-For", "<script>alert(1)</script>, 10.0.0.9");
        request.addHeader("X-Real-IP", "a-very-long-value-that-would-not-fit-the-column-and-is-no-address-at-all");

        assertThat(ClientAddress.of(request)).isEqualTo("10.0.0.3");
    }

    private static final Set<String> MAY_READ_THE_CONNECTION = Set.of(
        "ClientAddress.java",
        // Its own, documented rule: the proxy headers only when app.rate-limit.trust-proxy says so.
        "RateLimitFilter.java");

    @Test
    @DisplayName("no other class reads the connection's address on its own")
    void oneRuleForEveryRow() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            List<String> own = files
                .filter(file -> file.toString().endsWith(".java"))
                .filter(file -> !MAY_READ_THE_CONNECTION.contains(file.getFileName().toString()))
                .filter(file -> read(file).contains(".getRemoteAddr()"))
                .map(file -> file.getFileName().toString())
                .toList();
            assertThat(own).as("use ClientAddress.of(request) or ClientAddress.current()").isEmpty();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("the device name comes from fixed lists, never from the header's own text")
    void deviceNames() {
        assertThat(ClientDevice.label(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"))
            .isEqualTo("Chrome, Windows");
        assertThat(ClientDevice.label(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Safari/604.1"))
            .isEqualTo("Safari, iPhone");
        assertThat(ClientDevice.label("okhttp/4.12.0")).isEqualTo("Android app");
        assertThat(ClientDevice.label("eKeneya/12 CFNetwork/1490 Darwin/23.2.0")).isEqualTo("iOS app");
        assertThat(ClientDevice.label("<script>alert(1)</script>")).isNull();
        assertThat(ClientDevice.label(" ")).isNull();
    }
}
