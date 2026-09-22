package com.example.hms.service.pharmacy.partner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-54 — Parse fuzzy SMS replies from partner pharmacies.
 * <p>
 * Accepts messages such as:
 * <ul>
 *   <li>{@code "1 ABC12"}</li>
 *   <li>{@code "  1  abc12 "}</li>
 *   <li>{@code "oui ABC12"} (fuzzy accept)</li>
 *   <li>{@code "non ABC12"} (fuzzy reject)</li>
 *   <li>{@code "3 ABC12"} — dispense confirmation</li>
 * </ul>
 * A numeric action code is only honoured as the LEADING word of the body
 * (after trimming). Matching the digit anywhere used to turn
 * {@code "refus ABC12, il reste 1 boîte"} into an ACCEPT; a digit that is
 * not the first word is now ordinary text and the keyword branch decides.
 * <p>
 * Keywords are whole words: {@code "rupture de stock"} used to be an ACCEPT
 * because "stock" contains "ok". Refusal is looked for before acceptance, and
 * a body that carries words from more than one family is ambiguous and
 * refused rather than guessed.
 * Returns empty when the message is unparseable.
 */
@Component
@Slf4j
public class PartnerSmsReplyParser {

    public enum Action { ACCEPT, REJECT, CONFIRM_DISPENSE }

    /** Alphanumeric reference token (3..16 chars), case-insensitive when returned. */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("([A-Za-z0-9]{3,16})");

    /** Leading action code: {@code 1} accept, {@code 2} reject, {@code 3} dispensed. */
    private static final Pattern LEADING_CODE = Pattern.compile("^([123])\\b");

    /** Whole-word French / English keywords; (?U) so accented letters count as word characters. */
    private static final Pattern REJECT_WORDS = Pattern.compile("(?U)\\b(non|refus\\w*|rejet\\w*)\\b");
    private static final Pattern ACCEPT_WORDS = Pattern.compile("(?U)\\b(oui|ok|accept\\w*)\\b");
    private static final Pattern DISPENSE_WORDS =
            Pattern.compile("(?U)\\b(d[ée]liv\\w*|livr[ée]\\w*|dispen\\w*)\\b");

    public record ParsedReply(Action action, String refToken) { }

    public Optional<ParsedReply> parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Optional.empty();
        }
        String lower = rawBody.toLowerCase(java.util.Locale.ROOT).trim();

        Action action = detectAction(lower);
        if (action == null) {
            return Optional.empty();
        }

        String token = extractToken(lower);
        if (token == null) {
            return Optional.empty();
        }

        return Optional.of(new ParsedReply(action, token.toUpperCase(java.util.Locale.ROOT)));
    }

    private static Action detectAction(String lower) {
        // Numeric code first, and only as the leading word: "1 ABC12" is an
        // accept, "refus ABC12 il reste 1 boîte" is not.
        Matcher code = LEADING_CODE.matcher(lower);
        if (code.find()) {
            return switch (code.group(1)) {
                case "1" -> Action.ACCEPT;
                case "2" -> Action.REJECT;
                default -> Action.CONFIRM_DISPENSE;
            };
        }
        // Keyword fallback: refusal first, whole words only, one family only.
        boolean reject = REJECT_WORDS.matcher(lower).find();
        boolean accept = ACCEPT_WORDS.matcher(lower).find();
        boolean dispense = DISPENSE_WORDS.matcher(lower).find();
        int families = (reject ? 1 : 0) + (accept ? 1 : 0) + (dispense ? 1 : 0);
        if (families > 1) {
            log.info("Partner SMS reply ambiguous (reject={}, accept={}, dispense={}); ignored",
                    reject, accept, dispense);
            return null;
        }
        if (reject) {
            return Action.REJECT;
        }
        if (accept) {
            return Action.ACCEPT;
        }
        if (dispense) {
            return Action.CONFIRM_DISPENSE;
        }
        return null;
    }

    private static String extractToken(String lower) {
        // Strip the leading single-digit action code if present so it's not picked as the token.
        String stripped = lower.replaceAll("^\\s*\\d\\b", " ").trim();
        Matcher m = TOKEN_PATTERN.matcher(stripped);
        while (m.find()) {
            String candidate = m.group(1);
            // skip fuzzy action words so they are not treated as tokens
            if (isActionWord(candidate)) {
                continue;
            }
            // require at least one digit OR at least one letter that is not purely an action word
            return candidate;
        }
        return null;
    }

    private static boolean isActionWord(String s) {
        String l = s.toLowerCase(java.util.Locale.ROOT);
        return switch (l) {
            case "oui", "non", "ok", "accept", "refus", "rejet", "rejete",
                 "deliv", "delivre", "delivr", "livr", "livre", "dispen", "dispense" -> true;
            default -> false;
        };
    }
}
