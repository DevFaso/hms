package com.example.hms.utility;

import com.example.hms.exception.FieldBusinessException;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QA r65 D2500 / D2501: what a super-admin may type for a hospital's code,
 * currency and time zone. One place, so the create and the update paths
 * refuse the same values with the same field and message.
 *
 * <ul>
 *   <li><b>Code</b>: 2 to 20 characters, capital letters, digits and hyphens,
 *       starting and ending with a letter or a digit ({@code TWP},
 *       {@code CMT-C5F20}). Stored in capitals. It prefixes every invoice
 *       number ({@code InvoiceNumberAllocator.prefixOf}), which is why it
 *       holds no space or punctuation.</li>
 *   <li><b>Currency</b>: XOF or XAF, the two CFA francs. Every amount in
 *       billing is a whole number of francs (QA D184: "the franc CFA has no
 *       minor unit"), and every sentence, invoice PDF and e-mail the server
 *       composes prints « F CFA » ({@code PdfInvoiceService.money}, the
 *       billing message bundles). Both currencies read « F CFA », so both are
 *       printed truthfully today; another currency would be labelled wrong on
 *       paper and is refused here until those texts take the hospital's
 *       currency.</li>
 *   <li><b>Time zone</b>: an IANA region id the JVM knows
 *       ({@code Africa/Ouagadougou}). A free-text zone was accepted on the
 *       organisation and silently fell back to the server's zone
 *       ({@code HospitalCalendar.zoneOf}); a hospital's is validated.</li>
 * </ul>
 */
public final class HospitalLocaleRules {

    /** The currency of every hospital that names none. */
    public static final String DEFAULT_CURRENCY = "XOF";

    /** The currencies a hospital may bill in today: the CFA francs (West and Central Africa). */
    public static final Set<String> SUPPORTED_CURRENCIES = Set.of(DEFAULT_CURRENCY, "XAF");

    public static final String FIELD_CODE = "code";
    public static final String FIELD_CURRENCY = "currencyCode";
    public static final String FIELD_TIME_ZONE = "timeZone";

    static final String MSG_CODE_INVALID = "hospital.code.invalid";
    static final String MSG_CURRENCY_INVALID = "hospital.currency.invalid";
    static final String MSG_TIME_ZONE_INVALID = "hospital.timeZone.invalid";

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,18}[A-Z0-9]$");

    private HospitalLocaleRules() {
    }

    /** True when nothing was typed: the caller keeps its default (generate, XOF, the organisation's zone). */
    public static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }

    /** The code as stored (trimmed, capitals), or a refusal on the {@code code} field. */
    public static String requireCode(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new FieldBusinessException(FIELD_CODE, MSG_CODE_INVALID);
        }
        return code;
    }

    /** The ISO 4217 code as stored (capitals), or a refusal on the {@code currencyCode} field. */
    public static String requireCurrency(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_CURRENCIES.contains(code)) {
            throw new FieldBusinessException(FIELD_CURRENCY, MSG_CURRENCY_INVALID);
        }
        return code;
    }

    /** The IANA zone id as stored, or a refusal on the {@code timeZone} field. */
    public static String requireTimeZone(String raw) {
        String id = raw == null ? "" : raw.trim();
        if (!ZoneId.getAvailableZoneIds().contains(id)) {
            throw new FieldBusinessException(FIELD_TIME_ZONE, MSG_TIME_ZONE_INVALID);
        }
        return id;
    }
}
