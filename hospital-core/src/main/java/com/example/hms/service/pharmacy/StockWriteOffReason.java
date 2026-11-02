package com.example.hms.service.pharmacy;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * QA D1243 (round 25): a stock adjustment with the reason "Détérioration"
 * and a quantity of +2 ADDED two units. A write-off reason (damage, expiry,
 * loss, theft, breakage) only ever removes stock; a count variance or a
 * controlled-drug discrepancy may go either way.
 *
 * <p>The adjustment's reason is stored as {@code CODE: notes} by the portal
 * ({@code stock-adjustment.ts}), {@code CODE} alone or as the word itself by
 * other clients. The code (or the whole reason when it has no code) is read
 * in English, French and Spanish, accents ignored.
 */
public final class StockWriteOffReason {

    /** The refusal's message key, on the {@code quantity} field. */
    public static final String MUST_REMOVE_KEY = "stockTransaction.writeOff.mustRemove";

    private static final Set<String> WRITE_OFF = Set.of(
        // The portal's codes and the legacy ones it maps (QA D337).
        "DAMAGE", "DAMAGED", "EXPIRY_WRITE_OFF", "EXPIRED", "EXPIRY", "WRITE_OFF",
        "LOSS", "LOST", "THEFT", "STOLEN", "BREAKAGE", "BROKEN",
        // The same reasons written as words.
        "DETERIORATION", "PEREMPTION", "PERIME", "PERTE", "VOL", "CASSE", "MISE AU REBUT",
        "DETERIORO", "CADUCADO", "VENCIDO", "PERDIDA", "ROBO", "ROTURA");

    /** Reasons that may move stock either way (a count found more or less than recorded). */
    static final Set<String> TWO_WAY = Set.of("CYCLE_COUNT_VARIANCE", "CONTROLLED_DISCREPANCY");

    /** True when {@code code} is a known reason code, either way (StockWriteOffReasonTest). */
    static boolean isClassified(String code) {
        return isWriteOff(code) || TWO_WAY.contains(code);
    }

    private StockWriteOffReason() {
    }

    /** True when the reason writes stock off: a damage, expiry, loss, theft or breakage. */
    public static boolean isWriteOff(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        int colon = reason.indexOf(':');
        String code = (colon >= 0 ? reason.substring(0, colon) : reason).trim();
        String plain = Normalizer.normalize(code, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
            .toUpperCase(Locale.ROOT);
        return WRITE_OFF.contains(plain) || WRITE_OFF.contains(plain.replace(' ', '_'));
    }
}
