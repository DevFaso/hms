package com.example.hms.utility;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The account links that are e-mailed or texted (activation, password reset), built in one place.
 *
 * <p>A link carries one opaque, single-use, short-lived token and nothing else: never the address, the username
 * or a code. The token sits in the URL fragment, which a browser keeps to itself: it is not sent to the host that
 * serves the portal, so it reaches no access log and no {@code Referer}. The portal page reads it, removes it from
 * the address bar and exchanges it by POST ({@code /auth/verify-email}, {@code /auth/password/confirm}).
 * {@code AccountLinkGuardTest} keeps link building here and query strings out of it.
 */
public final class ActivationLinks {

    private ActivationLinks() {
    }

    /** The e-mailed activation link: {@code {portal}/verify#token=…}. */
    public static String activation(String frontendBaseUrl, String token) {
        return frontendBaseUrl + "/verify#token=" + encode(token);
    }

    /** The password-reset (or first-password) link: {@code {portal}/reset-password#token=…}. */
    public static String passwordReset(String frontendBaseUrl, String token) {
        return frontendBaseUrl + "/reset-password#token=" + encode(token);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
