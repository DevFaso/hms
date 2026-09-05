package com.example.hms.service.webhook;

import com.example.hms.exception.BusinessException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Gate on webhook endpoint URLs (Tier 2 item 45): the URL is
 * admin-supplied and HMS will POST to it from inside the deployment, so
 * an unchecked value is an SSRF vector into the private network.
 *
 * <p>What this refuses: non-HTTPS schemes, userinfo tricks, localhost
 * and *.local/*.internal names, and any host that is (or resolves to) a
 * loopback, private, link-local, or multicast address at REGISTRATION
 * time. What it deliberately does not attempt: defeating DNS rebinding
 * between registration and delivery — that needs resolution pinning in
 * the HTTP client and is out of scope for v1; the registrant is an
 * authenticated hospital admin, not an anonymous user.
 */
public final class WebhookUrlValidator {

    private WebhookUrlValidator() {
    }

    public static void requireDeliverable(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("The webhook URL is not a valid URL.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BusinessException("Webhook URLs must use https.");
        }
        if (uri.getUserInfo() != null) {
            throw new BusinessException("Webhook URLs must not carry credentials.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException("The webhook URL needs a host.");
        }
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".local") || lower.endsWith(".internal")) {
            throw new BusinessException("Webhook URLs must point at a public host.");
        }
        try {
            // EVERY A/AAAA answer must be public: a name whose first
            // answer is public but whose alternate is private would
            // otherwise pass registration and deliver internally.
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isNonPublic(address)) {
                    throw new BusinessException("Webhook URLs must point at a public host.");
                }
            }
        } catch (UnknownHostException e) {
            throw new BusinessException(
                "The webhook host could not be resolved - check the URL.");
        }
    }

    private static boolean isNonPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        // IPv6 unique-local (fc00::/7): Java reports it as neither
        // site-local nor link-local, so it needs its own check.
        boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        return uniqueLocalIpv6
            || address.isLoopbackAddress()
            || address.isSiteLocalAddress()
            || address.isLinkLocalAddress()
            || address.isMulticastAddress()
            || address.isAnyLocalAddress();
    }
}
