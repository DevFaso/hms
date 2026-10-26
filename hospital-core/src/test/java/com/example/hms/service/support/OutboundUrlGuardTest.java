package com.example.hms.service.support;

import com.example.hms.exception.FieldBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The address rules the registration check and the delivery-time lookup
 * share (QA D898, webhook DNS rebinding). IP literals only: no DNS here.
 */
class OutboundUrlGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
        "169.254.169.254", "0.0.0.0", "0.1.2.3", "100.64.0.1", "100.127.255.254",
        "198.18.0.1", "198.19.255.255", "224.0.0.1", "240.0.0.1", "255.255.255.255",
        "::1", "::", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1",
        "::ffff:10.0.0.1", "64:ff9b::a9fe:a9fe", "2002:0a00:0001::1", "::7f00:1"
    })
    void anAddressInsideTheDeploymentIsNotPublic(String literal) throws UnknownHostException {
        assertThat(OutboundUrlGuard.isPublic(InetAddress.getByName(literal))).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.1", "172.15.0.1", "172.32.0.1",
        "198.17.0.1", "198.20.0.1", "223.255.255.254", "2606:4700:4700::1111",
        "64:ff9b::808:808", "2002:0808:0808::1"
    })
    void aPublicAddressIsPublic(String literal) throws UnknownHostException {
        assertThat(OutboundUrlGuard.isPublic(InetAddress.getByName(literal))).as(literal).isTrue();
    }

    @Test
    void oneNonPublicAnswerAmongPublicOnesRefusesTheName() throws UnknownHostException {
        InetAddress[] answers = {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.5")};
        assertThatThrownBy(() -> OutboundUrlGuard.requireAllPublic("mixed.example.test", answers))
            .isInstanceOf(OutboundUrlGuard.NonPublicAddressException.class)
            .hasMessageContaining("mixed.example.test");
    }

    @Test
    void anEmptyAnswerIsUnresolvableNotPublic() {
        assertThatThrownBy(() -> OutboundUrlGuard.requireAllPublic("empty.example.test", new InetAddress[0]))
            .isInstanceOf(UnknownHostException.class)
            .isNotInstanceOf(OutboundUrlGuard.NonPublicAddressException.class);
    }

    @Test
    void theRegistrationCheckStillRefusesAPrivateLiteralAndTheMetadataService() {
        assertThatThrownBy(() -> OutboundUrlGuard.requirePublicHttps("https://169.254.169.254/latest", "url", "webhook.url"))
            .isInstanceOf(FieldBusinessException.class)
            .hasMessage("webhook.url.publicHostRequired");
        assertThatThrownBy(() -> OutboundUrlGuard.requirePublicHttps("https://100.64.0.7/hook", "url", "webhook.url"))
            .isInstanceOf(FieldBusinessException.class)
            .hasMessage("webhook.url.publicHostRequired");
    }

    @Test
    void theShapeCheckNeedsNoLookup() {
        assertThat(OutboundUrlGuard.requirePublicHttpsShape("https://hooks.example.test/x", "url", "webhook.url"))
            .isEqualTo("hooks.example.test");
        assertThatThrownBy(() -> OutboundUrlGuard.requirePublicHttpsShape("http://hooks.example.test/x", "url", "webhook.url"))
            .hasMessage("webhook.url.httpsRequired");
        assertThatThrownBy(() -> OutboundUrlGuard.requirePublicHttpsShape("https://user:pw@hooks.example.test/", "url", "webhook.url"))
            .hasMessage("webhook.url.credentials");
        assertThatThrownBy(() -> OutboundUrlGuard.requirePublicHttpsShape("https://api.internal/", "url", "webhook.url"))
            .hasMessage("webhook.url.publicHostRequired");
    }
}
