package com.example.hms.service.support;

import com.example.hms.exception.FieldBusinessException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF gate on admin-supplied URLs that HMS later calls from inside the
 * deployment: webhook endpoints (Tier 2 item 45) and the DHIS2 base URL
 * (QA D898). An unchecked value lets an admin point the server at the
 * cloud metadata service, localhost, or the private network.
 *
 * <p>What this refuses: non-HTTPS schemes, userinfo tricks, localhost
 * and *.local/*.internal names, and any host that is (or resolves to) a
 * loopback, private, link-local, multicast, wildcard or IPv6 unique-local
 * (fc00::/7) address. EVERY DNS answer is checked, not just the first.
 *
 * <p>What it deliberately does not attempt: pinning the resolved address
 * into the HTTP client. A caller that re-checks right before the call
 * (the DHIS2 export does) narrows the DNS-rebinding window to the gap
 * between this lookup and the client's own, but does not close it.
 *
 * <p>Each refusal is a bundle key {@code <keyPrefix>.<reason>} about one
 * request field, so the admin reads it in their language under that box.
 * Reasons: invalid, httpsRequired, credentials, hostRequired,
 * publicHostRequired, unresolvable.
 */
public final class OutboundUrlGuard {

    private OutboundUrlGuard() {
    }

    /**
     * @param url       the admin-supplied URL
     * @param field     the request property the refusal is about ("url", "baseUrl")
     * @param keyPrefix bundle-key prefix for the refusal reasons ("webhook.url")
     * @throws FieldBusinessException (400) when the URL may not be called
     */
    public static void requirePublicHttps(String url, String field, String keyPrefix) {
        if (url == null || url.isBlank()) {
            throw refusal(field, keyPrefix, "invalid");
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw refusal(field, keyPrefix, "invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw refusal(field, keyPrefix, "httpsRequired");
        }
        if (uri.getUserInfo() != null) {
            throw refusal(field, keyPrefix, "credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw refusal(field, keyPrefix, "hostRequired");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost")
            || lower.endsWith(".local") || lower.endsWith(".internal")) {
            throw refusal(field, keyPrefix, "publicHostRequired");
        }
        InetAddress[] answers;
        try {
            answers = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw refusal(field, keyPrefix, "unresolvable");
        }
        // EVERY A/AAAA answer must be public: a name whose first answer is
        // public but whose alternate is private would otherwise pass.
        for (InetAddress address : answers) {
            if (isNonPublic(address)) {
                throw refusal(field, keyPrefix, "publicHostRequired");
            }
        }
    }

    private static FieldBusinessException refusal(String field, String keyPrefix, String reason) {
        return new FieldBusinessException(field, keyPrefix + "." + reason);
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
