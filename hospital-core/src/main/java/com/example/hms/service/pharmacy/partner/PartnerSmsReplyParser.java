package com.example.hms.service.pharmacy.partner;

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
 *   <li>{@code "Rx:ABC12 1"} — reversed order</li>
 * </ul>
 * Returns empty when the message is unparseable.
 */
@Component
public class PartnerSmsReplyParser {

    public enum Action { ACCEPT, REJECT, CONFIRM_DISPENSE }

    /** Alphanumeric reference token (3..16 chars), case-insensitive when returned. */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("([A-Za-z0-9]{3,16})");

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
        // numeric code first
        if (lower.matches(".*\\b1\\b.*")) {
            return Action.ACCEPT;
        }
        if (lower.matches(".*\\b2\\b.*")) {
            return Action.REJECT;
        }
        if (lower.matches(".*\\b3\\b.*")) {
            return Action.CONFIRM_DISPENSE;
        }
        // French fuzzy fallback
        if (lower.contains("oui") || lower.contains("accept") || lower.contains("ok")) {
            return Action.ACCEPT;
        }
        if (lower.contains("non") || lower.contains("refus") || lower.contains("rejet")) {
            return Action.REJECT;
        }
        if (lower.contains("deliv") || lower.contains("délivr") || lower.contains("dispen")) {
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
                 "deliv", "delivre", "delivr", "dispen", "dispense" -> true;
            default -> false;
        };
    }
}
