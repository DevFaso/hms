package com.example.hms.service.notification;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code app.patient-notifications.email-recipient-allowlist} ({@code EMAIL_RECIPIENT_ALLOWLIST}):
 * the only addresses a non-production environment may send the patient notification emails to
 * (V312), mirroring {@code SMS_RECIPIENT_ALLOWLIST}. Dev holds made-up patients whose addresses can
 * belong to real people.
 *
 * <ul>
 *   <li><b>Unset or blank</b>: no restriction. Production's setting: nothing changes there.</li>
 *   <li><b>Anything else</b>: only the listed addresses (comma-separated, any case) are sent to; a
 *       send to another address is SKIPPED ({@code RECIPIENT_NOT_ALLOWED}). A value with no usable
 *       entry still restricts, and then allows no one: setting it can never mean "allow all".</li>
 * </ul>
 *
 * <p>It covers only the ledger's emails (appointment reminder, result available); the mail outbox's
 * other mails (activation, reset, booking confirmation) are not affected.
 */
public final class EmailRecipientAllowlist {

    private final boolean restricted;
    private final Set<String> addresses;

    private EmailRecipientAllowlist(boolean restricted, Set<String> addresses) {
        this.restricted = restricted;
        this.addresses = addresses;
    }

    /** Reads the property value; see the class comment for the exact rules. */
    public static EmailRecipientAllowlist parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new EmailRecipientAllowlist(false, Set.of());
        }
        Set<String> parsed = Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(entry -> entry.contains("@"))
            .map(entry -> entry.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
        return new EmailRecipientAllowlist(true, parsed);
    }

    public boolean restricts() {
        return restricted;
    }

    /** Whether {@code address} may be sent to (case-insensitive). */
    public boolean permits(String address) {
        return !restricted || (address != null && addresses.contains(address.trim().toLowerCase(Locale.ROOT)));
    }
}
