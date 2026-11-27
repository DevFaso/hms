package com.example.hms.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/**
 * The address a request came from, for the audit trail.
 *
 * <p>Only what one of our own hops recorded is believed. When the connection
 * itself comes from outside the platform's network, its address is the
 * answer and no header is read. When it comes from inside (the portal's
 * proxy, the platform's edge), {@code X-Forwarded-For} is read from the
 * right: each hop appends the address it saw, so the first entry from the
 * right that is not one of our own is the one our outermost hop recorded.
 * Entries further left were sent by the caller and are never used. With no
 * such entry, the connection's address stands.
 *
 * <p>"Our own" is the loopback, private, link-local, unique-local and
 * carrier-grade ranges, plus the prefixes in {@code app.audit.trusted-proxies}
 * for an edge that reaches us from a public address. Only a value shaped like
 * an IPv4 or IPv6 address is ever returned.
 */
public final class ClientAddress {

    /** The audit column's width. */
    private static final int MAX_LENGTH = 45;

    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2," + MAX_LENGTH + "}");
    private static final Pattern IPV4 = Pattern.compile(
        "(?:::ffff:)?(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}", Pattern.CASE_INSENSITIVE);

    /** Extra address prefixes of our own hops ({@code app.audit.trusted-proxies}), lower case. */
    private static final java.util.concurrent.atomic.AtomicReference<java.util.List<String>> EXTRA_TRUSTED =
        new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of());

    /** The loopback in the spellings a servlet container reports it. */
    private static final java.util.Set<String> IPV6_LOOPBACK = java.util.Set.of("::1", "0:0:0:0:0:0:0:1");

    private ClientAddress() {
    }

    /** Sets the extra trusted prefixes (an address, or its leading part ending in '.' or ':'). */
    public static void trustAlso(java.util.Collection<String> prefixes) {
        EXTRA_TRUSTED.set(prefixes == null ? java.util.List.of() : prefixes.stream()
            .filter(java.util.Objects::nonNull)
            .map(prefix -> prefix.trim().toLowerCase(java.util.Locale.ROOT))
            .filter(prefix -> !prefix.isEmpty())
            .toList());
    }

    /** The address of {@code request}, or {@code null} when there is none to read. */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String peer = addressOrNull(request.getRemoteAddr());
        if (peer != null && !isOurOwn(peer)) {
            return peer;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            String[] hops = forwarded.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = addressOrNull(hops[i]);
                if (hop == null) {
                    break; // not an address: nothing left of it is trusted either
                }
                if (!isOurOwn(hop)) {
                    return hop;
                }
            }
        }
        return peer;
    }

    /** The address of the request this thread is serving, or {@code null} outside one. */
    public static String current() {
        return of(currentRequest());
    }

    static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
            ? attributes.getRequest()
            : null;
    }

    /** Whether {@code address} is one of the platform's own hops. */
    static boolean isOurOwn(String address) {
        String a = address.toLowerCase(java.util.Locale.ROOT);
        return isConfiguredHop(a) || isOwnIpv6(a) || isOwnIpv4(a);
    }

    /** One of the {@code app.audit.trusted-proxies} entries: the address itself, or a prefix ending in '.' or ':'. */
    private static boolean isConfiguredHop(String a) {
        for (String prefix : EXTRA_TRUSTED.get()) {
            boolean isPrefix = prefix.endsWith(".") || prefix.endsWith(":");
            if (a.equals(prefix) || (isPrefix && a.startsWith(prefix))) {
                return true;
            }
        }
        return false;
    }

    /** The IPv6 loopback, unique-local (fc00::/7) or link-local (fe80::/10) ranges. */
    private static boolean isOwnIpv6(String a) {
        return IPV6_LOOPBACK.contains(a) || a.startsWith("fc") || a.startsWith("fd") || a.startsWith("fe80:");
    }

    /** The IPv4 loopback, private, link-local and carrier-grade ranges (also as an IPv4-mapped IPv6 address). */
    private static boolean isOwnIpv4(String a) {
        java.util.regex.Matcher v4 = IPV4.matcher(a);
        if (!v4.matches()) {
            return false;
        }
        int first = Integer.parseInt(v4.group(1));
        int second = Integer.parseInt(v4.group(2));
        return switch (first) {
            case 10, 127 -> true;
            case 172 -> second >= 16 && second <= 31;
            case 192 -> second == 168;
            case 169 -> second == 254;
            case 100 -> second >= 64 && second <= 127;
            default -> false;
        };
    }

    private static String addressOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return ADDRESS.matcher(trimmed).matches() ? trimmed : null;
    }
}
