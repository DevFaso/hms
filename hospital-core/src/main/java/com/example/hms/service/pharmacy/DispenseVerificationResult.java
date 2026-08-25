package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseCheck;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Outcome of a server-side dispense-time verification.
 *
 * <p>Same shape as {@code FiveRightsVerificationResult} on purpose — the
 * pharmacy UI and the eMAR UI render the same checklist widget over it, and
 * the two steps of the medication chain should not diverge in how they
 * report a failure.
 *
 * <p>A check that could not be evaluated is <em>absent</em> from the map
 * rather than recorded as a pass. That distinction is load-bearing here in a
 * way it is not at the bedside: the scan is optional at the counter, so "the
 * pharmacist did not scan the wristband" must never be storable as "the
 * right patient was confirmed".
 */
public final class DispenseVerificationResult {

    private final Map<DispenseCheck, Boolean> outcomes;
    private final Map<DispenseCheck, String> failureReasons;

    private DispenseVerificationResult(
        Map<DispenseCheck, Boolean> outcomes,
        Map<DispenseCheck, String> failureReasons
    ) {
        this.outcomes = outcomes;
        this.failureReasons = failureReasons;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<DispenseCheck, Boolean> getOutcomes() {
        return outcomes;
    }

    public Map<DispenseCheck, String> getFailureReasons() {
        return failureReasons;
    }

    /** Every check that was evaluated passed. */
    public boolean allPassed() {
        return outcomes.values().stream().allMatch(Boolean.TRUE::equals);
    }

    /** Whether this specific check was evaluated at all. */
    public boolean wasEvaluated(DispenseCheck check) {
        return outcomes.containsKey(check);
    }

    /** Checks that failed (empty when {@link #allPassed()} is true). */
    public Set<DispenseCheck> failedChecks() {
        EnumSet<DispenseCheck> failed = EnumSet.noneOf(DispenseCheck.class);
        outcomes.forEach((check, ok) -> {
            if (Boolean.FALSE.equals(ok)) {
                failed.add(check);
            }
        });
        return failed;
    }

    /** Joined failure reasons, for the refusal message the pharmacist sees. */
    public String failureSummary() {
        return String.join("; ", failureReasons.values());
    }

    public static final class Builder {
        private final Map<DispenseCheck, Boolean> outcomes = new EnumMap<>(DispenseCheck.class);
        private final Map<DispenseCheck, String> failureReasons = new EnumMap<>(DispenseCheck.class);

        public Builder pass(DispenseCheck check) {
            outcomes.put(check, Boolean.TRUE);
            failureReasons.remove(check);
            return this;
        }

        public Builder fail(DispenseCheck check, String reason) {
            outcomes.put(check, Boolean.FALSE);
            if (reason != null && !reason.isBlank()) {
                failureReasons.put(check, reason);
            }
            return this;
        }

        /**
         * Whether this check has already been recorded as failed. Lets a
         * later stage skip a check an earlier one has settled, so the
         * pharmacist keeps the first and most specific reason rather than a
         * generic one written over the top of it.
         */
        public boolean hasFailed(DispenseCheck check) {
            return Boolean.FALSE.equals(outcomes.get(check));
        }

        public DispenseVerificationResult build() {
            return new DispenseVerificationResult(
                Map.copyOf(outcomes),
                Map.copyOf(failureReasons)
            );
        }
    }
}
