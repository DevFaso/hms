package com.example.hms.utility;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one rule for what counts as a deliverable email address, shared by the
 * mail sender ({@code EmailServiceImpl.validateAddresses}) and by anything
 * that accepts an address it will later mail, so a request is never accepted
 * for an address the sender would then refuse.
 */
public final class EmailAddresses {

    /** The users.email column length. */
    public static final int MAX_LENGTH = 100;

    /** Simple RFC check; the character-class exclusions prevent backtracking (ReDoS). */
    private static final Pattern DELIVERABLE =
        Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    private EmailAddresses() {
    }

    /** True when the sender will accept this address. */
    public static boolean isDeliverable(String address) {
        return address != null && !address.isBlank() && DELIVERABLE.matcher(address).matches();
    }

    /** Trimmed and lower-cased as registration stores it ({@code UserMapper.normalizeEmail}); blank is null. */
    public static String normalize(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
