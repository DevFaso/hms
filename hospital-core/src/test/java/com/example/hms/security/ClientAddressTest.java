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

    private static MockHttpServletRequest from(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @org.junit.jupiter.api.AfterEach
    void noExtraHops() {
        ClientAddress.trustAlso(List.of());
    }

    @Test
    @DisplayName("behind our own hops: the address the outermost of them recorded")
    void theAddressOurOwnHopRecorded() {
        assertThat(ClientAddress.of(from("10.0.0.5", "41.203.10.7, 10.0.0.3"))).isEqualTo("41.203.10.7");
        assertThat(ClientAddress.of(from("fd12:a9af::1", "2001:db8::7, fd12:a9af::9"))).isEqualTo("2001:db8::7");
        assertThat(ClientAddress.of(from("127.0.0.1", " 41.203.10.7 "))).isEqualTo("41.203.10.7");
    }

    @Test
    @DisplayName("an entry the caller put in front is never the answer")
    void whatTheCallerSentIsNotBelieved() {
        // Each hop appends what it saw: the caller's own entries are to the left of it.
        assertThat(ClientAddress.of(from("10.0.0.5", "203.0.113.7, 41.203.10.7, 10.0.0.5")))
            .isEqualTo("41.203.10.7");
        assertThat(ClientAddress.of(from("10.0.0.5", "203.0.113.7, 198.51.100.9, 41.203.10.7")))
            .isEqualTo("41.203.10.7");
    }

    @Test
    @DisplayName("a connection from outside our network: its own address, whatever the headers say")
    void aDirectConnectionIsItsOwnAddress() {
        MockHttpServletRequest direct = from("41.203.10.7", "203.0.113.7");
        direct.addHeader("X-Real-IP", "198.51.100.9");

        assertThat(ClientAddress.of(direct)).isEqualTo("41.203.10.7");
    }

    @Test
    @DisplayName("nothing forwarded, or only our own hops: the connection's address; X-Real-IP is not read")
    void fallsBackToTheConnection() {
        MockHttpServletRequest real = from("10.0.0.3", null);
        real.addHeader("X-Real-IP", "203.0.113.7");

        assertThat(ClientAddress.of(real)).isEqualTo("10.0.0.3");
        assertThat(ClientAddress.of(from("10.0.0.3", "192.168.1.4, 10.0.0.9"))).isEqualTo("10.0.0.3");
        assertThat(ClientAddress.of(null)).isNull();
    }

    @Test
    @DisplayName("a header that is not an address puts nothing in the row, and hides nothing to its left")
    void onlyAnAddressIsKept() {
        assertThat(ClientAddress.of(from("10.0.0.3", "<script>alert(1)</script>, 10.0.0.9"))).isEqualTo("10.0.0.3");
        assertThat(ClientAddress.of(from("10.0.0.3", "203.0.113.7, not-an-address, 10.0.0.9"))).isEqualTo("10.0.0.3");
    }

    @Test
    @DisplayName("an edge on a public address is one of ours once it is configured")
    void aConfiguredEdgeIsSkipped() {
        MockHttpServletRequest request = from("10.0.0.5", "203.0.113.7, 41.203.10.7, 162.220.232.9");
        assertThat(ClientAddress.of(request)).isEqualTo("162.220.232.9");

        ClientAddress.trustAlso(List.of(" 162.220.232. "));
        assertThat(ClientAddress.of(request)).isEqualTo("41.203.10.7");
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
