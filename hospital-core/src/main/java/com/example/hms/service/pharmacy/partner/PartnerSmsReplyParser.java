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
 *   <li>{@code "« 1 ABC12 »"} — the form {@link PartnerSmsTemplates} asks for,
 *       quoted verbatim</li>
 *   <li>{@code "oui ABC12"} (fuzzy accept)</li>
 *   <li>{@code "non ABC12"} (fuzzy reject)</li>
 *   <li>{@code "3 ABC12"} — dispense confirmation</li>
 *   <li>the whole offer quoted back with an answer appended</li>
 * </ul>
 * A numeric action code is only honoured at the START of the body, after any
 * quotes or guillemets. Matching the digit anywhere used to turn
 * {@code "refus ABC12, il reste 1 boîte"} into an ACCEPT.
 * <p>
 * Keywords are whole words ({@code "rupture de stock"} used to be an ACCEPT
 * because "stock" contains "ok") and a refusal word decides on its own —
 * "non, on ne peut pas livrer" is a refusal, not a confusing mixture. Only an
 * acceptance mixed with a dispense claim, with no refusal, is ambiguous.
 * <p>
 * Many handsets quote the message they are answering. The offer itself ends in
 * "…pour accepter, « 2 ABC12 » pour refuser", so a quoted copy carries the word
 * "refuser" and would read as a refusal whatever the pharmacy actually wrote:
 * the instruction sentence this class's own templates emit is therefore removed
 * before any keyword is looked for.
 */
@Component
@Slf4j
public class PartnerSmsReplyParser {

    public enum Action { ACCEPT, REJECT, CONFIRM_DISPENSE }

    /** Alphanumeric reference token (3..16 chars), case-insensitive when returned. */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("([A-Za-z0-9]{3,16})");

    /** Quotes a handset may wrap the reply in, and that the templates use themselves. */
    private static final String QUOTES = "[\\s\"'«»“”„`]*";

    /** Leading action code: {@code 1} accept, {@code 2} reject, {@code 3} dispensed. */
    private static final Pattern LEADING_CODE = Pattern.compile("^" + QUOTES + "([123])\\b");

    /** The same leading run, for stripping before the reference is looked for. */
    private static final Pattern LEADING_CODE_STRIP = Pattern.compile("^" + QUOTES + "[123]\\b");

    /**
     * The reply instructions {@link PartnerSmsTemplates} appends to the offer and
     * the reminder, in any quoted-back copy: "Répondez « 1 ABC12 » pour accepter,
     * « 2 ABC12 » pour refuser."
     */
    private static final Pattern INSTRUCTION_SENTENCE =
            Pattern.compile("r[ée]pondez\\b.*?refuser\\s*\\.?", Pattern.DOTALL);

    /** The offer's own prefix, so the reference survives a quoted-back copy. */
    private static final Pattern OFFER_REFERENCE = Pattern.compile("hms\\s+rx\\s+([a-z0-9]{3,16})");

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
        String sanitized = stripQuotedInstructions(lower);

        Action action = detectAction(lower, sanitized);
        if (action == null) {
            return Optional.empty();
        }

        String token = extractToken(lower, sanitized);
        if (token == null) {
            return Optional.empty();
        }

        return Optional.of(new ParsedReply(action, token.toUpperCase(java.util.Locale.ROOT)));
    }

    /**
     * Remove the instruction sentence our own templates emit, so a handset that
     * quotes the offer back does not answer on the pharmacy's behalf.
     */
    private static String stripQuotedInstructions(String lower) {
        return INSTRUCTION_SENTENCE.matcher(lower).replaceAll(" ").trim();
    }

    private static Action detectAction(String lower, String sanitized) {
        // The leading code is decisive and is read from the body as sent: a
        // pharmacy that copies "« 1 ABC12 »" out of the offer has accepted.
        Matcher code = LEADING_CODE.matcher(lower);
        if (code.find()) {
            return switch (code.group(1)) {
                case "1" -> Action.ACCEPT;
                case "2" -> Action.REJECT;
                default -> Action.CONFIRM_DISPENSE;
            };
        }
        // Keyword fallback, whole words, over the text the pharmacy actually
        // added. A refusal wins outright: the words a pharmacy refuses with
        // routinely name what it cannot do ("non, on ne peut pas livrer"), so
        // treating that as a mixture would silently drop real refusals. Only an
        // acceptance sitting next to a dispense claim is genuinely undecidable.
        boolean reject = REJECT_WORDS.matcher(sanitized).find();
        boolean accept = ACCEPT_WORDS.matcher(sanitized).find();
        boolean dispense = DISPENSE_WORDS.matcher(sanitized).find();
        if (reject) {
            return Action.REJECT;
        }
        if (accept && dispense) {
            log.info("Partner SMS reply ambiguous (accept and dispense, no refusal); ignored");
            return null;
        }
        if (accept) {
            return Action.ACCEPT;
        }
        if (dispense) {
            return Action.CONFIRM_DISPENSE;
        }
        return null;
    }

    private static String extractToken(String lower, String sanitized) {
        // A quoted-back offer carries its own reference right after "HMS Rx",
        // which is more reliable than the first word that looks like a token.
        Matcher offer = OFFER_REFERENCE.matcher(lower);
        if (offer.find()) {
            return offer.group(1);
        }
        // Otherwise: drop the leading action code (with any quotes around it)
        // so it is not mistaken for the reference, then take the first
        // token-shaped word that is not one of the action words.
        String stripped = LEADING_CODE_STRIP.matcher(sanitized).replaceFirst(" ").trim();
        Matcher m = TOKEN_PATTERN.matcher(stripped);
        while (m.find()) {
            String candidate = m.group(1);
            if (isActionWord(candidate)) {
                continue;
            }
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
