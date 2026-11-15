package com.example.hms.utility;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Which phone numbers HMS pays to text. Every SMS is charged, and texting
 * foreign premium routes is the costly "SMS pumping" fraud, so the gateway only
 * sends to the eight WAEMU countries (the same list as the sister project
 * ez-ticket). A Burkina Faso number must be a full E.164 number: {@code +226}
 * then exactly eight digits.
 *
 * <p>The input is the E.164 form ({@code +22670707070}); callers turn a number
 * typed at the desk into that form first ({@link PhoneNumbers#toInternationalDigits}).
 */
public final class SmsCountryPolicy {

    /** Burkina Faso, Côte d'Ivoire, Mali, Senegal, Niger, Togo, Benin, Guinea-Bissau. */
    public static final List<String> ALLOWED_CALLING_CODES =
        List.of("226", "225", "223", "221", "227", "228", "229", "245");

    /** E.164: a plus, then 8 to 15 digits, the first not zero. */
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");

    /** Burkina Faso: {@code +226} and an eight-digit national number. */
    private static final Pattern BURKINA_FASO = Pattern.compile("\\+226\\d{8}");

    private SmsCountryPolicy() {
    }

    /** Whether {@code e164} is a well-formed E.164 number. */
    public static boolean isE164(String e164) {
        return e164 != null && E164.matcher(e164).matches();
    }

    /**
     * Whether HMS may text {@code e164}: well formed, in an allowed country, and
     * for Burkina Faso exactly eight national digits.
     */
    public static boolean allows(String e164) {
        if (!isE164(e164)) {
            return false;
        }
        if (e164.startsWith("+226")) {
            return BURKINA_FASO.matcher(e164).matches();
        }
        return ALLOWED_CALLING_CODES.stream().anyMatch(code -> e164.startsWith("+" + code));
    }
}
