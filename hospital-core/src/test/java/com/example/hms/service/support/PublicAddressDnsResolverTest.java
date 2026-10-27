package com.example.hms.service.support;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Webhook DNS rebinding: a name that answered with a public address when it
 * was registered answers with the metadata service when it is called. The
 * lookup the client connects with is the one that is checked.
 */
class PublicAddressDnsResolverTest {

    /** Public on the first lookup, the cloud metadata address on every later one. */
    private static final class Rebinding implements DnsResolver {
        private final AtomicInteger lookups = new AtomicInteger();

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return new InetAddress[] {InetAddress.getByName(
                lookups.getAndIncrement() == 0 ? "93.184.215.14" : "169.254.169.254")};
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    @Test
    void theSecondAnswerOfARebindingNameIsRefused() throws UnknownHostException {
        PublicAddressDnsResolver resolver = new PublicAddressDnsResolver(new Rebinding());

        assertThat(resolver.resolve("hooks.example.test")).hasSize(1);
        assertThatThrownBy(() -> resolver.resolve("hooks.example.test"))
            .isInstanceOf(OutboundUrlGuard.NonPublicAddressException.class);
    }

    @Test
    void theHostAndPortLookupIsCheckedTooAndNeverLeftUnresolved() throws UnknownHostException {
        PublicAddressDnsResolver resolver = new PublicAddressDnsResolver(new Rebinding());

        List<InetSocketAddress> first = resolver.resolve("hooks.example.test", 443);
        assertThat(first).singleElement().satisfies(address -> {
            assertThat(address.isUnresolved()).isFalse();
            assertThat(address.getPort()).isEqualTo(443);
        });
        assertThatThrownBy(() -> resolver.resolve("hooks.example.test", 443))
            .isInstanceOf(OutboundUrlGuard.NonPublicAddressException.class);
    }

    @Test
    void anUpstreamWithNoAnswerIsUnresolvable() {
        DnsResolver silent = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return new InetAddress[0];
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        PublicAddressDnsResolver resolver = new PublicAddressDnsResolver(silent);
        assertThatThrownBy(() -> resolver.resolve("hooks.example.test", 443))
            .isInstanceOf(UnknownHostException.class);
    }
}
