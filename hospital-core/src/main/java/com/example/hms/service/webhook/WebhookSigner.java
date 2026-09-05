package com.example.hms.service.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Webhook delivery signing (Tier 2 item 45) — the repo's first real HMAC
 * (the partner-sms webhook compares a raw shared secret; this signs the
 * body). The signed string is {@code timestamp + "." + body} so a
 * receiver that checks the timestamp gets replay protection for free.
 *
 * <p>Header contract (documented for receivers):
 * <pre>
 *   X-HMS-Webhook-Signature: v1=&lt;hex(HMAC-SHA256(secret, timestamp + "." + body))&gt;
 *   X-HMS-Webhook-Timestamp: &lt;unix seconds&gt;
 * </pre>
 */
public final class WebhookSigner {

    public static final String SIGNATURE_HEADER = "X-HMS-Webhook-Signature";
    public static final String TIMESTAMP_HEADER = "X-HMS-Webhook-Timestamp";
    public static final String EVENT_HEADER = "X-HMS-Webhook-Event";
    public static final String DELIVERY_HEADER = "X-HMS-Webhook-Delivery";

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {
    }

    /** Returns the header value, {@code v1=<hex>}. */
    public static String sign(String secret, long timestampEpochSeconds, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(
                (timestampEpochSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("v1=");
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandatory in every JRE - this cannot happen on
            // a functioning runtime, and a delivery must not go out
            // unsigned, so fail the attempt loudly.
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
