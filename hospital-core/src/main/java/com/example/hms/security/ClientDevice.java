package com.example.hms.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A short name for the device a request came from ("Chrome, Windows",
 * "Android app"), read from {@code User-Agent}, for the sign-in rows a person
 * reads in their own activity. Only names from the fixed lists below are ever
 * produced: the header's own text is never stored.
 */
public final class ClientDevice {

    /** The prefix of an audit row's details that carries the device name. */
    public static final String DETAILS_PREFIX = "device=";

    private ClientDevice() {
    }

    /** The device name of {@code request}, or {@code null} when nothing is recognised. */
    public static String of(HttpServletRequest request) {
        return request == null ? null : label(request.getHeader("User-Agent"));
    }

    /** The audit details of the request this thread is serving ({@code device=…}), or {@code null}. */
    public static String currentDetails() {
        String label = of(ClientAddress.currentRequest());
        return label == null ? null : DETAILS_PREFIX + label;
    }

    static String label(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String app = app(userAgent);
        if (app != null) {
            return app;
        }
        String browser = browser(userAgent);
        String system = system(userAgent);
        if (browser == null) {
            return system;
        }
        return system == null ? browser : browser + ", " + system;
    }

    private static String app(String userAgent) {
        if (userAgent.contains("okhttp") || userAgent.contains("Dalvik")) {
            return "Android app";
        }
        if (userAgent.contains("CFNetwork") || userAgent.contains("Darwin/")) {
            return "iOS app";
        }
        return null;
    }

    private static String browser(String userAgent) {
        if (userAgent.contains("Edg/") || userAgent.contains("EdgA/") || userAgent.contains("EdgiOS/")) {
            return "Edge";
        }
        if (userAgent.contains("OPR/")) {
            return "Opera";
        }
        if (userAgent.contains("Firefox/") || userAgent.contains("FxiOS/")) {
            return "Firefox";
        }
        if (userAgent.contains("Chrome/") || userAgent.contains("CriOS/")) {
            return "Chrome";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return null;
    }

    private static String system(String userAgent) {
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        }
        if (userAgent.contains("iPad")) {
            return "iPad";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Mac OS X") || userAgent.contains("Macintosh")) {
            return "Mac";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return null;
    }
}
