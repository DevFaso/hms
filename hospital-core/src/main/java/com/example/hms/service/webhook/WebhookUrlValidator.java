package com.example.hms.service.webhook;

import com.example.hms.service.support.OutboundUrlGuard;

/**
 * Gate on webhook endpoint URLs (Tier 2 item 45): the URL is
 * admin-supplied and HMS will POST to it from inside the deployment, so
 * an unchecked value is an SSRF vector into the private network.
 *
 * <p>The checks live in {@link OutboundUrlGuard}, shared with the DHIS2
 * base URL (QA D898). They run at REGISTRATION time only: delivery does
 * not re-resolve, so DNS rebinding between registration and delivery is
 * not defeated here; the registrant is an authenticated hospital admin.
 *
 * <p>QA D873: each refusal is a bundle key about the {@code url} field, so
 * the admin reads it in their language, under the URL box.
 */
public final class WebhookUrlValidator {

    private WebhookUrlValidator() {
    }

    public static void requireDeliverable(String url) {
        OutboundUrlGuard.requirePublicHttps(url, "url", "webhook.url");
    }
}
