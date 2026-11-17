package com.example.hms.utility;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The e-mailed activation link, built in one place.
 *
 * <p>The address and the token are query values and must be URL-encoded: an
 * address such as {@code a+b@example.com} put into the link raw arrives at
 * {@code /auth/verify-email} as {@code a b@example.com} (a {@code +} in a query
 * decodes to a space), so the account could never be activated from its own
 * e-mail.
 */
public final class ActivationLinks {

    private ActivationLinks() {
    }

    public static String build(String frontendBaseUrl, String email, String token) {
        return frontendBaseUrl + "/verify?email=" + encode(email) + "&token=" + encode(token);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
