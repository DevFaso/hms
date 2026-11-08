package com.example.hms.service.pharmacy;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * QA D1243 (round 25): a stock adjustment with the reason "Détérioration"
 * and a quantity of +2 ADDED two units. A write-off reason (damage, expiry,
 * loss, theft, breakage, disposal) only ever removes stock; a count variance
 * or a controlled-drug discrepancy may go either way.
 *
 * <p>The adjustment's reason is stored as {@code CODE: notes} by the portal
 * ({@code stock-adjustment.ts}), {@code CODE} alone or as free text by other
 * clients. QA D1261 (round 26): the round-25 reader matched the WHOLE text
 * before the colon against a word list, so "Mise au rebut (péremption)" and
 * "Perte de stock: carton égaré" still added stock. The part before the colon
 * (the whole reason when it has none) is now read word by word, accents and
 * case ignored, in English, French and Spanish: a write-off code, word or
 * phrase anywhere in it - singular or plural, masculine or feminine - makes
 * it a write-off. A reason that is ambiguous therefore errs on the side that
 * cannot add stock.
 */
public final class StockWriteOffReason {

    /** The refusal's message key, on the {@code quantity} field. */
    public static final String MUST_REMOVE_KEY = "stockTransaction.writeOff.mustRemove";

    /** The portal's codes and the legacy ones it maps (QA D337), matched as the whole code. */
    private static final Set<String> WRITE_OFF_CODES = Set.of(
        "DAMAGE", "DAMAGED", "EXPIRY_WRITE_OFF", "EXPIRED", "EXPIRY", "WRITE_OFF",
        "LOSS", "LOST", "THEFT", "STOLEN", "BREAKAGE", "BROKEN");

    /** Words that name a write-off on their own, in any of the three languages (accents folded). */
    private static final Set<String> WRITE_OFF_WORDS = words();

    /** Phrases whose words only name a write-off together. */
    private static final List<String> WRITE_OFF_PHRASES = List.of(
        "MISE AU REBUT", "MIS AU REBUT", "WRITE OFF", "WRITTEN OFF", "DATE DE PEREMPTION", "FECHA DE CADUCIDAD");

    /** Reasons that may move stock either way (a count found more or less than recorded). */
    static final Set<String> TWO_WAY = Set.of("CYCLE_COUNT_VARIANCE", "CONTROLLED_DISCREPANCY");

    private static final Pattern MARKS = Pattern.compile("\\p{M}");
    private static final Pattern NOT_A_LETTER = Pattern.compile("[^A-Z]+");

    private StockWriteOffReason() {
    }

    /** True when {@code code} is a known reason code, either way (StockWriteOffReasonTest). */
    static boolean isClassified(String code) {
        return isWriteOff(code) || TWO_WAY.contains(code);
    }

    /** True when the reason writes stock off: a damage, expiry, loss, theft, breakage or disposal. */
    public static boolean isWriteOff(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        int colon = reason.indexOf(':');
        String code = fold(colon >= 0 ? reason.substring(0, colon) : reason);
        if (TWO_WAY.contains(code.replace(' ', '_'))) {
            return false;
        }
        if (WRITE_OFF_CODES.contains(code) || WRITE_OFF_CODES.contains(code.replace(' ', '_'))) {
            return true;
        }
        String phrase = " " + NOT_A_LETTER.matcher(code).replaceAll(" ").trim() + " ";
        for (String word : phrase.trim().split(" ")) {
            if (WRITE_OFF_WORDS.contains(word)) {
                return true;
            }
        }
        for (String writeOff : WRITE_OFF_PHRASES) {
            if (phrase.contains(" " + writeOff + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String fold(String text) {
        return MARKS.matcher(Normalizer.normalize(text.trim(), Normalizer.Form.NFD)).replaceAll("")
            .toUpperCase(Locale.ROOT);
    }

    private static Set<String> words() {
        Set<String> out = new HashSet<>();
        // English nouns and participles (the codes' words, read inside a sentence too).
        out.addAll(List.of("DAMAGE", "DAMAGED", "EXPIRY", "EXPIRED", "LOSS", "LOST", "THEFT", "STOLEN",
            "BREAKAGE", "BROKEN", "DISPOSAL", "DISPOSED", "DISCARDED", "SPOILED", "SPOILT"));
        // French nouns.
        out.addAll(List.of("DETERIORATION", "PEREMPTION", "PERTE", "PERTES", "VOL", "VOLS", "BRIS", "REBUT"));
        // French participles (masculine, feminine, plurals); CASSE is the noun too.
        for (String stem : List.of("DETERIORE", "ENDOMMAGE", "ABIME", "PERIME", "EXPIRE", "PERDU", "VOLE", "CASSE")) {
            out.addAll(List.of(stem, stem + "E", stem + "S", stem + "ES"));
        }
        // Spanish nouns.
        out.addAll(List.of("DETERIORO", "CADUCIDAD", "VENCIMIENTO", "PERDIDA", "PERDIDAS", "ROBO", "ROBOS",
            "ROTURA", "ROTURAS", "DESCARTE"));
        // Spanish participles: -o, -a, -os, -as.
        for (String stem : List.of("DETERIORAD", "DANAD", "CADUCAD", "VENCID", "PERDID", "ROBAD", "ROT", "DESECHAD")) {
            out.addAll(List.of(stem + "O", stem + "A", stem + "OS", stem + "AS"));
        }
        return Set.copyOf(out);
    }
}
