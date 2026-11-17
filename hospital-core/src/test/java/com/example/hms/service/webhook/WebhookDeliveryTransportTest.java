package com.example.hms.service.webhook;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook DNS rebinding (QA round 14): the URL was checked at registration
 * only, so a name that later answers with an address inside the deployment
 * was called there. The transport's client resolves through the public-only
 * resolver, so the address it would connect to is the one that is checked.
 * Fake DNS; nothing here reaches the network except the last test, which
 * aims at a documentation address with a short timeout.
 */
class WebhookDeliveryTransportTest {

    private static final class FakeDns implements DnsResolver {
        private final Map<String, String> answers;
        private final AtomicInteger lookups = new AtomicInteger();

        FakeDns(Map<String, String> answers) {
            this.answers = answers;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            lookups.incrementAndGet();
            String answer = answers.get(host);
            if (answer == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName(answer)};
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }

    private WebhookDeliveryTransport transport;

    @AfterEach
    void close() throws IOException {
        if (transport != null) {
            transport.close();
        }
    }

    private WebhookDeliveryTransport transport(FakeDns dns) {
        WebhookProperties properties = new WebhookProperties();
        properties.setConnectTimeoutMs(300);
        properties.setReadTimeoutMs(300);
        transport = new WebhookDeliveryTransport(properties, dns);
        return transport;
    }

    private static WebhookDeliveryTransport.Result post(WebhookDeliveryTransport transport, String url) {
        return transport.post(url, "{}", "sig", 1L, "APPOINTMENT_BOOKED", "d-1");
    }

    @Test
    void aRegisteredNameThatNowAnswersWithTheMetadataServiceIsNotCalled() {
        FakeDns dns = new FakeDns(Map.of("hooks.example.test", "169.254.169.254"));

        WebhookDeliveryTransport.Result result = post(transport(dns), "https://hooks.example.test/hook");

        assertThat(result.refused()).isTrue();
        assertThat(result.httpStatus()).isNull();
        assertThat(result.error()).contains("non-public address");
        assertThat(dns.lookups.get()).as("the client's own lookup is the one checked").isPositive();
    }

    @Test
    void aNameThatNowAnswersWithLoopbackOrAPrivateAddressIsNotCalled() {
        FakeDns dns = new FakeDns(Map.of("loop.example.test", "127.0.0.1", "lan.example.test", "10.0.0.8"));
        WebhookDeliveryTransport client = transport(dns);

        assertThat(post(client, "https://loop.example.test/hook").refused()).isTrue();
        assertThat(post(client, "https://lan.example.test/hook").refused()).isTrue();
    }

    @Test
    void aPrivateIpLiteralIsNotCalled() {
        WebhookDeliveryTransport.Result result = post(transport(new FakeDns(Map.of("127.0.0.1", "127.0.0.1"))), "https://127.0.0.1/hook");

        assertThat(result.refused()).isTrue();
    }

    @Test
    void aStoredUrlThatIsNoLongerHttpsIsNotCalled() {
        FakeDns dns = new FakeDns(Map.of("hooks.example.test", "93.184.215.14"));

        WebhookDeliveryTransport.Result result = post(transport(dns), "http://hooks.example.test/hook");

        assertThat(result.refused()).isTrue();
        assertThat(result.error()).contains("webhook.url.httpsRequired");
        assertThat(dns.lookups.get()).isZero();
    }

    @Test
    void anUnresolvableNameIsAnOrdinaryRetryableFailure() {
        WebhookDeliveryTransport.Result result = post(transport(new FakeDns(Map.of())), "https://gone.example.test/hook");

        assertThat(result.refused()).isFalse();
        assertThat(result.success()).isFalse();
    }

    @Test
    void aPublicAnswerIsConnectedToNotRefused() {
        // 192.0.2.0/24 is public by these rules and routes nowhere: the
        // attempt fails on the connect, which proves it was let through.
        FakeDns dns = new FakeDns(Map.of("hooks.example.test", "192.0.2.10"));

        WebhookDeliveryTransport.Result result = post(transport(dns), "https://hooks.example.test/hook");

        assertThat(result.refused()).isFalse();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotNull();
    }
}
