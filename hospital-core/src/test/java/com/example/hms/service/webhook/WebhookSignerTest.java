package com.example.hms.service.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract receivers will implement against (Tier 2 item
 * 45): {@code v1=hex(HMAC-SHA256(secret, timestamp + "." + body))}. A
 * silent change here breaks every partner's verification code at once.
 */
class WebhookSignerTest {

    @Test
    @DisplayName("the signature is v1= plus 64 lowercase hex chars, deterministic")
    void formatIsPinned() {
        String first = WebhookSigner.sign("whsec_test", 1_762_200_000L, "{\"event\":\"PING\"}");
        String second = WebhookSigner.sign("whsec_test", 1_762_200_000L, "{\"event\":\"PING\"}");

        assertThat(first).matches("^v1=[0-9a-f]{64}$").isEqualTo(second);
    }

    @Test
    @DisplayName("the signed string is exactly timestamp dot body - pinned against an independent HMAC")
    void signedStringIsPinned() throws Exception {
        String secret = "whsec_test";
        long timestamp = 1_762_200_000L;
        String body = "{\"event\":\"APPOINTMENT_BOOKED\"}";

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "v1=" + HexFormat.of().formatHex(
            mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));

        assertThat(WebhookSigner.sign(secret, timestamp, body)).isEqualTo(expected);
    }

    @Test
    @DisplayName("secret, timestamp and body each change the signature")
    void everyInputMatters() {
        String base = WebhookSigner.sign("whsec_a", 1000L, "{}");

        assertThat(WebhookSigner.sign("whsec_b", 1000L, "{}")).isNotEqualTo(base);
        assertThat(WebhookSigner.sign("whsec_a", 1001L, "{}")).isNotEqualTo(base);
        assertThat(WebhookSigner.sign("whsec_a", 1000L, "{ }")).isNotEqualTo(base);
    }
}
