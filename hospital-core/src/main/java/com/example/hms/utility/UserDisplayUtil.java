package com.example.hms.utility;

import com.example.hms.model.User;

/**
 * Shared utility for resolving a human-readable display name from a {@link User}.
 *
 * <p>Centralised here to avoid duplicating the same logic across multiple service classes
 * (e.g. {@code UserServiceImpl}, {@code PasswordResetServiceImpl}).</p>
 */
public final class UserDisplayUtil {

    private UserDisplayUtil() {
        // utility class — not instantiable
    }

    /**
     * Returns a presentable name for the user.
     *
     * <ul>
     *   <li>"{@code firstName lastName}" when both are set</li>
     *   <li>"{@code firstName}" or "{@code lastName}" alone when only one is set</li>
     *   <li>{@code username} when neither name is set</li>
     *   <li>{@code "there"} as a last-resort fallback</li>
     * </ul>
     */
    public static String resolveDisplayName(User user) {
        if (user == null) return "there";
        String first = user.getFirstName() != null ? user.getFirstName().strip() : "";
        String last  = user.getLastName()  != null ? user.getLastName().strip()  : "";
        if (!first.isBlank() && !last.isBlank()) return first + " " + last;
        if (!first.isBlank()) return first;
        if (!last.isBlank())  return last;
        return user.getUsername() != null ? user.getUsername() : "there";
    }
}
