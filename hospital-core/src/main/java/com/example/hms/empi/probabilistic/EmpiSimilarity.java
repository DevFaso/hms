package com.example.hms.empi.probabilistic;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pure-function similarity helpers used by
 * {@link EmpiProbabilisticMatcher} (roadmap row 25 follow-on).
 *
 * <p>The Fellegi-Sunter framework combines per-field agreement
 * probabilities into a composite score. HMS uses normalised per-field
 * similarities in {@code [0, 1]} and a weighted sum — the operator
 * tunes the weights + the {@code minScore} threshold against the
 * labelled audit set (still pending — see the row-25 cell).
 *
 * <p>No external dependencies (e.g. Apache Commons Text
 * JaroWinkler): pure-Java implementations keep the EMPI module
 * dependency surface bounded for the West-Africa-deployment threat
 * model (offline-capable, minimal supply chain).
 */
public final class EmpiSimilarity {

    private EmpiSimilarity() {}

    /**
     * Normalised Levenshtein similarity in {@code [0, 1]}. Returns 1.0
     * for identical strings, 0.0 for completely different strings.
     * Case-insensitive, whitespace-trimmed; blank inputs on either side
     * collapse to 0.0 (the caller treats a blank as "no information").
     *
     * <p>Chosen over Jaro-Winkler for the foundation pass because:
     * normalised Levenshtein is simpler to verify against a labelled
     * set, has no prefix bias (Jaro-Winkler's prefix bonus inflates
     * scores for common given-name prefixes like "Mary"-"Mariam"
     * — false-positive territory in mixed-population datasets), and
     * is a well-known building block the audit-set follow-on can swap
     * out for JW when ROC analysis recommends it.
     */
    public static double nameSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        String x = a.trim().toLowerCase(java.util.Locale.ROOT);
        String y = b.trim().toLowerCase(java.util.Locale.ROOT);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        if (x.equals(y)) return 1.0;
        int distance = levenshtein(x, y);
        int longest = Math.max(x.length(), y.length());
        return 1.0 - ((double) distance / (double) longest);
    }

    /**
     * Combined first+last name similarity. Two normalised
     * Levenshtein scores blended with last-name weight 0.6 and
     * first-name weight 0.4 (last names carry more discriminating
     * power in most populations — Smith family members share the
     * surname, not the given name). Blank fields on either side
     * weight the present field to 1.0.
     */
    public static double combinedNameSimilarity(
        String firstA, String lastA, String firstB, String lastB
    ) {
        boolean hasFirst = isPresent(firstA) && isPresent(firstB);
        boolean hasLast = isPresent(lastA) && isPresent(lastB);
        if (!hasFirst && !hasLast) return 0.0;
        if (!hasFirst) return nameSimilarity(lastA, lastB);
        if (!hasLast) return nameSimilarity(firstA, firstB);
        return 0.4 * nameSimilarity(firstA, firstB)
             + 0.6 * nameSimilarity(lastA, lastB);
    }

    /**
     * DOB similarity with month-tolerance: exact match = 1.0, same
     * year+month = 0.85, same year = 0.6, within ±1 year = 0.4,
     * otherwise 0.0. Captures the common data-quality pattern where
     * paper-form DOBs are recorded with the wrong day-of-month but
     * the year is reliable.
     *
     * <p>Returns 0.0 when either input is null — same blank-input
     * contract as {@link #nameSimilarity}.
     */
    public static double dobSimilarity(LocalDate a, LocalDate b) {
        if (a == null || b == null) return 0.0;
        if (a.equals(b)) return 1.0;
        if (a.getYear() == b.getYear() && a.getMonthValue() == b.getMonthValue()) return 0.85;
        if (a.getYear() == b.getYear()) return 0.6;
        long yearGap = Math.abs(ChronoUnit.YEARS.between(a, b));
        if (yearGap <= 1L) return 0.4;
        return 0.0;
    }

    /** Sex/gender match: case-insensitive equality on the trimmed values; 0.0 on either-side blank. */
    public static double sexSimilarity(String a, String b) {
        if (!isPresent(a) || !isPresent(b)) return 0.0;
        return a.trim().equalsIgnoreCase(b.trim()) ? 1.0 : 0.0;
    }

    /** National-ID match: trimmed exact (case-sensitive); 0.0 on either-side blank. */
    public static double nationalIdSimilarity(String a, String b) {
        if (!isPresent(a) || !isPresent(b)) return 0.0;
        return a.trim().equals(b.trim()) ? 1.0 : 0.0;
    }

    private static boolean isPresent(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static int levenshtein(String s, String t) {
        int n = s.length();
        int m = t.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char si = s.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (si == t.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }
}
