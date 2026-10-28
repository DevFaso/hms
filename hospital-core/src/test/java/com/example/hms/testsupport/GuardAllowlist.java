package com.example.hms.testsupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The allowlist shape the round-16 guards share (docs/qa/regression-guards.md):
 * a site key, how many places hold it today, and why it is tolerated — a QA
 * defect id or a reason a reviewer can check. A guard fails when a site exceeds
 * its allowance (the class came back) and when a listed site has shrunk (the
 * fix landed: lower the count in the same change, so the gain cannot be spent
 * again). One copy of the comparison, next to {@link SourceScan}.
 */
public final class GuardAllowlist {

    /** How many places a site may hold today, and why. */
    public record Allowance(int count, String reason) { }

    private GuardAllowlist() {
    }

    /**
     * {@code allow("site", 2, "QA D123: …", "other", 1, "…")}: triples of site, count,
     * reason. A hand-edited list that lost an element fails here, naming the entry,
     * rather than with a ClassCastException out of the guard's static initialiser.
     */
    public static Map<String, Allowance> allow(Object... siteCountReason) {
        if (siteCountReason.length % 3 != 0) {
            throw new IllegalArgumentException("allow(...) takes site, count, reason triples; got "
                + siteCountReason.length + " values, the last one: " + siteCountReason[siteCountReason.length - 1]);
        }
        Map<String, Allowance> out = new TreeMap<>();
        for (int i = 0; i < siteCountReason.length; i += 3) {
            if (!(siteCountReason[i] instanceof String site) || !(siteCountReason[i + 1] instanceof Integer count)
                || !(siteCountReason[i + 2] instanceof String reason)) {
                throw new IllegalArgumentException("allow(...) entry " + (i / 3) + " is not (String site, int count,"
                    + " String reason): " + siteCountReason[i] + ", " + siteCountReason[i + 1] + ", " + siteCountReason[i + 2]);
            }
            if (count < 1 || reason.isBlank()) {
                throw new IllegalArgumentException("allow(...) entry for " + site + " needs a count >= 1 and a reason");
            }
            out.put(site, new Allowance(count, reason));
        }
        return out;
    }

    /** One occurrence per element: the shape for a guard whose sites are a set. */
    public static Map<String, Integer> counts(Collection<String> sites) {
        Map<String, Integer> out = new TreeMap<>();
        for (String site : sites) {
            out.merge(site, 1, Integer::sum);
        }
        return out;
    }

    /** Sites found more often than allowed — a new instance of the class, or a copy of an allowed one. */
    public static List<String> overAllowance(Map<String, Integer> actual, Map<String, Allowance> allowed) {
        List<String> fresh = new ArrayList<>();
        actual.forEach((site, n) -> {
            int limit = allowed.containsKey(site) ? allowed.get(site).count() : 0;
            if (n > limit) {
                fresh.add(site + " ×" + n + " (allowed " + limit + ")");
            }
        });
        return fresh;
    }

    /** Allowed sites found less often than listed — the fix landed and the allowance must shrink. */
    public static List<String> stale(Map<String, Integer> actual, Map<String, Allowance> allowed) {
        List<String> stale = new ArrayList<>();
        allowed.forEach((site, allowance) -> {
            int n = actual.getOrDefault(site, 0);
            if (n < allowance.count()) {
                stale.add(site + ": " + n + " left, " + allowance.count() + " allowed — lower it");
            }
        });
        return stale;
    }
}
