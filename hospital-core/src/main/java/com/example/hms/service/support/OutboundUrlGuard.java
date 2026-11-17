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
 * <p>A check at registration alone does not stop DNS rebinding: the name
 * can answer with a public address when it is saved and a private one
 * when it is called. The webhook delivery closes that by resolving through
 * {@link PublicAddressDnsResolver}, which applies {@link #isPublic} to the
 * very lookup the HTTP client connects with, so the address checked is the
 * address used. Other callers re-check with {@link #requirePublicHttps}
 * before each call.
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
        String host = requirePublicHttpsShape(url, field, keyPrefix);
        try {
            resolvePublic(host);
        } catch (NonPublicAddressException e) {
            throw refusal(field, keyPrefix, "publicHostRequired");
        } catch (UnknownHostException e) {
            throw refusal(field, keyPrefix, "unresolvable");
        }
    }

    /**
     * Every check that needs no DNS lookup: HTTPS, no userinfo, a host, and
     * not a local name. Returns the host. The delivery path runs this before
     * each call and leaves the address check to the lookup it connects with.
     *
     * @throws FieldBusinessException (400) when the URL may not be called
     */
    public static String requirePublicHttpsShape(String url, String field, String keyPrefix) {
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
        return host;
    }

    /**
     * Resolve {@code host} and require EVERY A/AAAA answer to be public: a
     * name whose first answer is public but whose alternate is private would
     * otherwise pass.
     *
     * @throws NonPublicAddressException when any answer is not public
     * @throws UnknownHostException      when the name does not resolve
     */
    public static InetAddress[] resolvePublic(String host) throws UnknownHostException {
        return requireAllPublic(host, InetAddress.getAllByName(host));
    }

    /** {@code answers} unchanged when every one is public. */
    public static InetAddress[] requireAllPublic(String host, InetAddress[] answers) throws UnknownHostException {
        if (answers == null || answers.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : answers) {
            if (!isPublic(address)) {
                throw new NonPublicAddressException(host);
            }
        }
        return answers;
    }

    /** A lookup that answered with an address HMS may not call. */
    public static final class NonPublicAddressException extends UnknownHostException {
        private static final long serialVersionUID = 1L;

        NonPublicAddressException(String host) {
            super(host + " resolves to a non-public address");
        }
    }

    private static FieldBusinessException refusal(String field, String keyPrefix, String reason) {
        return new FieldBusinessException(field, keyPrefix + "." + reason);
    }

    /**
     * Whether HMS may call {@code address}: not loopback, private, link-local,
     * multicast or wildcard, not IPv6 unique-local (fc00::/7), not carrier-grade
     * NAT (100.64.0.0/10, the range some platforms use for their own private
     * network), not 0.0.0.0/8, the benchmarking range 198.18.0.0/15 or the
     * reserved 240.0.0.0/4, and not an IPv6 address that only wraps one of
     * those (IPv4-compatible, NAT64 64:ff9b::/96, 6to4 2002::/16).
     */
    public static boolean isPublic(InetAddress address) {
        if (address == null
            || address.isLoopbackAddress()
            || address.isSiteLocalAddress()
            || address.isLinkLocalAddress()
            || address.isMulticastAddress()
            || address.isAnyLocalAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 4 ? isPublicIpv4(bytes) : isPublicIpv6(bytes);
    }

    private static boolean isPublicIpv4(byte[] b) {
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return first != 0                                   // 0.0.0.0/8
            && first != 10 && first != 127                  // private, loopback
            && !(first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10
            && !(first == 169 && second == 254)             // link-local
            && !(first == 172 && second >= 16 && second <= 31)
            && !(first == 192 && second == 168)
            && !(first == 198 && (second == 18 || second == 19)) // 198.18.0.0/15
            && first < 224;                                 // multicast, reserved, broadcast
    }

    private static boolean isPublicIpv6(byte[] b) {
        // fc00::/7 unique-local: Java reports it as neither site- nor link-local.
        if ((b[0] & 0xfe) == 0xfc) {
            return false;
        }
        // 2002::/16 6to4: the IPv4 address sits in bytes 2-5.
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
            return isPublicIpv4(new byte[] {b[2], b[3], b[4], b[5]});
        }
        // 64:ff9b::/96 NAT64 and ::/96 IPv4-compatible: the IPv4 address is the last 4 bytes.
        boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64
            && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b && zero(b, 4, 12);
        boolean ipv4Compatible = zero(b, 0, 12);
        if (nat64 || ipv4Compatible) {
            return isPublicIpv4(new byte[] {b[12], b[13], b[14], b[15]});
        }
        return true;
    }

    private static boolean zero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
