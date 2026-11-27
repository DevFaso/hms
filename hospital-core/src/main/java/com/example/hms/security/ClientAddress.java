package com.example.hms.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/**
 * The address a request came from, for the audit trail: the first address of
 * {@code X-Forwarded-For} (the portal's proxy and the platform's edge add
 * it), else {@code X-Real-IP}, else the connection's own address. Only a
 * value shaped like an IPv4 or IPv6 address is kept, so a header can put
 * nothing else in an audit row.
 */
public final class ClientAddress {

    /** The audit column's width. */
    private static final int MAX_LENGTH = 45;

    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2," + MAX_LENGTH + "}");

    private ClientAddress() {
    }

    /** The address of {@code request}, or {@code null} when there is none to read. */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String first = first(request.getHeader("X-Forwarded-For"));
        if (first != null) {
            return first;
        }
        String real = addressOrNull(request.getHeader("X-Real-IP"));
        return real != null ? real : addressOrNull(request.getRemoteAddr());
    }

    /** The first address of an {@code X-Forwarded-For} value, or {@code null} when it is not one. */
    public static String first(String forwardedFor) {
        if (forwardedFor == null) {
            return null;
        }
        int comma = forwardedFor.indexOf(',');
        return addressOrNull(comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor);
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

    private static String addressOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return ADDRESS.matcher(trimmed).matches() ? trimmed : null;
    }
}
