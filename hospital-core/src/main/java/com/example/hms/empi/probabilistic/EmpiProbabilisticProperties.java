package com.example.hms.empi.probabilistic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flag for EMPI v0 probabilistic patient matching (roadmap row
 * 25, v1.1 / Patient Identity).
 *
 * <p>Default {@code false}. When off, the candidate-match endpoint
 * ({@code POST /api/empi/candidates}) returns {@code 404} and
 * {@link EmpiProbabilisticMatcher#findCandidates} short-circuits to
 * an empty list. <strong>Even when the flag is on, the foundation
 * pass still returns an empty list</strong> — the Fellegi-Sunter
 * scorer body is the named row-25 follow-on, deferred deliberately
 * so threshold tuning happens against a labelled audit set rather
 * than intuition. The wire contract + DTO shape are stable today so
 * the receptionist UI can be wired against the empty response.
 * There is no auto-merge or auto-link behaviour at any flag setting.
 * (Javadoc accuracy fix from PR #349 Copilot review.)
 *
 * <p>Companion to the deterministic EMPI path (see
 * {@code empi-identity} skill): the deterministic alias resolver
 * stays the primary identity contract; this scorer only fires when
 * the receptionist explicitly asks for "fuzzy candidates" against a
 * draft patient entry.
 */
@ConfigurationProperties(prefix = "app.empi.probabilistic")
public class EmpiProbabilisticProperties {

    /** Master switch. */
    private boolean enabled = false;

    /**
     * Minimum normalized score (0.0 - 1.0) for a candidate to be
     * surfaced to the receptionist. Defaults to 0.7 — calibrated for
     * the deliverable's "≥90% recall on labelled audit set" target
     * once the audit set lands.
     */
    private double minScore = 0.7;

    /** Maximum candidates returned per query. */
    private int maxCandidates = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }
}
