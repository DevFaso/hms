package com.example.hms.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A host names this machine in any notation, without a DNS lookup")
class LocalAddressCheckTest {

    @ParameterizedTest(name = "[{index}] ''{0}'' is local")
    @DisplayName("loopback, unspecified and localhost forms are local")
    @ValueSource(strings = {
        // names
        "localhost", "LOCALHOST", "localhost.", "LocalHost.", "redis.localhost", " localhost ",
        // IPv4 loopback, the whole 127.0.0.0/8, in every notation a resolver accepts
        "127.0.0.1", "127.0.0.2", "127.0.1.1", "127.255.255.254", "127.1", "127.0.1", "2130706433",
        "0x7f.0.0.1", "0x7f000001", "0177.0.0.1", "127.0.0.1.",
        // leading zeros: decimal to Java and the Redis client, octal to inet_aton; local either way is refused
        "0127.0.0.1", "00127.0.0.1", "0x7f.1", "127.000.000.001", "0127.1",
        // IPv4 unspecified
        "0.0.0.0", "0", "0x0",
        // IPv6 loopback in any form
        "::1", "[::1]", "0:0:0:0:0:0:0:1", "0000:0000:0000:0000:0000:0000:0000:0001", "::0:1", "::1%lo0",
        "[::1%25lo0]",
        // IPv6 unspecified
        "::", "[::]", "0:0:0:0:0:0:0:0",
        // IPv4-mapped and IPv4-compatible loopback / unspecified
        "::ffff:127.0.0.1", "::FFFF:127.0.0.2", "0:0:0:0:0:ffff:127.0.0.1", "::ffff:7f00:1", "[::ffff:127.0.0.1]",
        "::ffff:0.0.0.0", "::127.0.0.1",
        // blank, or a literal that does not parse: cannot name the shared service either
        "", "   ", "[]", "256.0.0.1", "1.2.3.4.5", "127.0.0.0x", "0x", "1::2::3", "::ffff:127.0.0.256",
        "1:2:3:4:5:6:7:8:9", "gggg::1"
    })
    void localForms(String host) {
        assertThat(LocalAddressCheck.isLocal(host)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] ''{0}'' is not local")
    @DisplayName("a shared service's name or a routable address is not local")
    @ValueSource(strings = {
        "redis.railway.internal", "redis", "my-redis-123.example.com", "localhost.example.com", "notlocalhost",
        "10.0.0.5", "172.16.3.4", "192.168.1.10", "128.0.0.1", "126.255.255.255", "8.8.8.8", "10.1",
        "fd12:3456:789a::1", "[fd12:3456:789a::1]", "2001:db8::1", "::2", "::ffff:10.0.0.5", "fe80::1%eth0",
        "1:2:3:4:5:6:7:8", "1.2.3.example",
        // ambiguous forms whose every reading is a shared address
        "010.0.0.5", "0xa.0.0.5"
    })
    void sharedForms(String host) {
        assertThat(LocalAddressCheck.isLocal(host)).isFalse();
    }

    @ParameterizedTest(name = "[{index}] ''{0}''")
    @DisplayName("null counts as local")
    @NullSource
    void nullIsLocal(String host) {
        assertThat(LocalAddressCheck.isLocal(host)).isTrue();
    }
}
