package com.example.hms.service.pharmacy.partner;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-54 — Read a partner pharmacy's SMS reply.
 *
 * <p>This parser does not interpret prose. A reply is understood only in the
 * structured form the offer asks for — an action code with the reference,
 * either as the whole message ({@code "1 ABC12"}, {@code "« 1 ABC12 »"}) or as
 * the instructed {@code « 1 ABC12 »} inside one. Anything else is reported as
 * not understood so a person reads it and acts.
 *
 * <p>It used to guess: French keywords ("oui", "non", "délivré"), a code at the
 * start of any line, and a family-precedence rule to settle contradictions.
 * Each guess read text the pharmacy had not written — the offer quoted back by
 * the handset, with its own "pour refuser", its medication name and the
 * prescriber's note — and each fix for one misreading created another:
 * "à délivrer en une seule fois" made an acceptance ambiguous, a note
 * containing "non" turned one into a refusal, and a line of a quoted note
 * beginning "3 boîtes si possible" told a patient their medication had been
 * dispensed. The class of bug is removed rather than patched: a message that
 * is not the instructed reply reaches a human.
 *
 * <p>Handsets wrap replies in quotes and guillemets, so those are tolerated
 * around the structured form.
 */
@Component
public class PartnerSmsReplyParser {

    public enum Action { ACCEPT, REJECT, CONFIRM_DISPENSE }

    /** A reference token as {@code SmsPartnerNotificationChannel#buildRefToken} mints it. */
    private static final String REF = "([A-Za-z0-9]{3,16})";

    /** Quotes and guillemets a handset (or the offer itself) may wrap the reply in. */
    private static final Pattern LEADING_WRAPPER = Pattern.compile("^[\\s\"'«»“”„`]+");
    private static final Pattern TRAILING_WRAPPER = Pattern.compile("[\\s\"'«»“”„`.!]+$");

    /** The whole message is the instructed reply: "1 ABC12", "1-ABC12", "1 : ABC12". */
    private static final Pattern WHOLE_BODY = Pattern.compile("^([123])[\\s:.\\-]+" + REF + "$");

    /** The instructed reply as the offer prints it, inside a longer message. */
    private static final Pattern INSTRUCTED_FORM = Pattern.compile("«\\s*([123])\\s+" + REF + "\\s*»");

    /** The offer's own prefix, used only to work out which prescription a message is about. */
    private static final Pattern OFFER_REFERENCE = Pattern.compile("(?i)hms\\s+rx\\s+" + REF);

    /** Any word that is shaped like a reference, for the same purpose. */
    private static final Pattern REFERENCE_SHAPED = Pattern.compile("[A-Za-z0-9]{3,16}");

    /** Enough candidates to find the prescription, few enough to bound the lookups. */
    private static final int MAX_CANDIDATES = 8;

    public record ParsedReply(Action action, String refToken) { }

    /**
     * The reply, when it is the structured form the offer instructs. Empty for
     * anything else — including a message that merely quotes the offer back,
     * whose instructed forms contradict each other (ours prints both
     * {@code « 1 REF »} and {@code « 2 REF »}).
     */
    public Optional<ParsedReply> parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Optional.empty();
        }
        String core = unwrap(rawBody.trim());

        Matcher whole = WHOLE_BODY.matcher(core);
        if (whole.matches()) {
            return Optional.of(reply(whole.group(1), whole.group(2)));
        }
        return instructedForm(rawBody);
    }

    /**
     * References this message might be about, best first, for naming the
     * prescription when the reply could not be read. These are candidates to
     * look up and verify, never grounds to act.
     */
    public List<String> candidateReferences(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        Matcher instructed = INSTRUCTED_FORM.matcher(rawBody);
        while (instructed.find()) {
            candidates.add(upper(instructed.group(2)));
        }
        Matcher offer = OFFER_REFERENCE.matcher(rawBody);
        if (offer.find()) {
            candidates.add(upper(offer.group(1)));
        }
        Matcher shaped = REFERENCE_SHAPED.matcher(unwrap(rawBody.trim()));
        while (shaped.find() && candidates.size() < MAX_CANDIDATES) {
            candidates.add(upper(shaped.group()));
        }
        return new ArrayList<>(candidates).subList(0, Math.min(candidates.size(), MAX_CANDIDATES));
    }

    /**
     * The instructed form, and only when every occurrence agrees. A quoted-back
     * offer carries both codes for the same reference, so it decides nothing.
     */
    private static Optional<ParsedReply> instructedForm(String rawBody) {
        Matcher m = INSTRUCTED_FORM.matcher(rawBody);
        ParsedReply found = null;
        while (m.find()) {
            ParsedReply candidate = reply(m.group(1), m.group(2));
            if (found == null) {
                found = candidate;
            } else if (!found.equals(candidate)) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(found);
    }

    private static ParsedReply reply(String code, String ref) {
        return new ParsedReply(actionForCode(code), upper(ref));
    }

    private static Action actionForCode(String code) {
        return switch (code) {
            case "1" -> Action.ACCEPT;
            case "2" -> Action.REJECT;
            default -> Action.CONFIRM_DISPENSE;
        };
    }

    private static String unwrap(String body) {
        String withoutLead = LEADING_WRAPPER.matcher(body).replaceFirst("");
        return TRAILING_WRAPPER.matcher(withoutLead).replaceFirst("");
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
