package com.example.hms.service.integration;

import com.example.hms.utility.ActivationDeliveryTracker;
import com.example.hms.utility.SmsCountryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code app.sms.recipient-allowlist} ({@code SMS_RECIPIENT_ALLOWLIST}): the only
 * numbers a non-production environment may text. Dev and uat hold patients whose
 * made-up phone numbers can belong to real people, and every SMS is paid, so
 * there they set the list to the team's consented test numbers.
 *
 * <p>How the value is read, with no middle ground:
 * <ul>
 *   <li><b>Unset or blank</b> (only whitespace): <em>no restriction</em>. This is
 *       production's setting, and the startup log says so.</li>
 *   <li><b>Anything else</b>: <em>restricted</em>. Only the listed numbers
 *       (comma-separated, E.164, e.g. {@code +22670000001,+22670000002}) are
 *       texted. An entry that is not a valid E.164 number is ignored with an
 *       error log, never widened: a value whose entries are all invalid (or
 *       only commas) still restricts, and then allows no one. Setting the
 *       variable can never mean "allow all".</li>
 * </ul>
 *
 * <p>Both {@link IkoddiGateway} implementations check it before every paid
 * request (plain SMS and remote OTP sends), so no caller can bypass it.
 */
public final class SmsRecipientAllowlist {

    private static final Logger LOG = LoggerFactory.getLogger(SmsRecipientAllowlist.class);

    /** Message key of a send refused by the allowlist. */
    public static final String KEY_NOT_ALLOWLISTED = "sms.recipient.notAllowlisted";

    private static final SmsRecipientAllowlist UNRESTRICTED = new SmsRecipientAllowlist(false, Set.of());

    private final boolean restricted;
    private final Set<String> numbers;

    private SmsRecipientAllowlist(boolean restricted, Set<String> numbers) {
        this.restricted = restricted;
        this.numbers = numbers;
    }

    /** No restriction: what an unset or blank property gives. */
    public static SmsRecipientAllowlist unrestricted() {
        return UNRESTRICTED;
    }

    /** Reads the property value; see the class comment for the exact rules. */
    public static SmsRecipientAllowlist parse(String raw, String provider) {
        if (raw == null || raw.isBlank()) {
            LOG.info("{} SMS recipient allowlist is empty: every number may be texted (production setting; dev and "
                + "uat must set SMS_RECIPIENT_ALLOWLIST)", provider);
            return UNRESTRICTED;
        }
        Set<String> parsed = new LinkedHashSet<>();
        int invalid = 0;
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String compact = trimmed.replaceAll("[\\s.-]", "");
            if (SmsCountryPolicy.isE164(compact)) {
                parsed.add(compact);
            } else {
                invalid++;
            }
        }
        if (invalid > 0) {
            LOG.error("{} SMS recipient allowlist: {} entr{} ignored (not an E.164 number such as +22670000001)",
                provider, invalid, invalid == 1 ? "y" : "ies");
        }
        LOG.warn("{} SMS recipient allowlist ACTIVE: only {} number(s) may be texted; every other recipient is "
            + "refused", provider, parsed.size());
        return new SmsRecipientAllowlist(true, Collections.unmodifiableSet(parsed));
    }

    /** Whether the list restricts recipients at all. */
    public boolean restricts() {
        return restricted;
    }

    /** Whether {@code e164} ({@code +22670707070}) may be texted. */
    public boolean permits(String e164) {
        return !restricted || (e164 != null && numbers.contains(e164));
    }

    /** Number of listed recipients (0 when unrestricted). */
    public int size() {
        return numbers.size();
    }

    /** Logs a refused recipient, masked. */
    void logRefused(String provider, String e164) {
        String masked = ActivationDeliveryTracker.maskPhone(e164);
        LOG.warn("{} SMS not sent to {}: not on SMS_RECIPIENT_ALLOWLIST", provider, masked == null ? "<none>" : masked);
    }
}
