package com.example.hms.service.emar;

import com.example.hms.enums.FiveRightsCheck;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Outcome of a server-side bedside five-rights verification.
 *
 * <p>Holds the per-right pass/fail map plus the human-readable reason for any
 * failed right (e.g. {@code "scanned drug barcode does not match prescribed
 * medication code"}). The controller returns this verbatim to the eMAR UI so
 * the nurse can see exactly which right failed before deciding whether to
 * abort or override.
 */
public final class FiveRightsVerificationResult {

    private final Map<FiveRightsCheck, Boolean> outcomes;
    private final Map<FiveRightsCheck, String> failureReasons;

    private FiveRightsVerificationResult(
        Map<FiveRightsCheck, Boolean> outcomes,
        Map<FiveRightsCheck, String> failureReasons
    ) {
        this.outcomes = outcomes;
        this.failureReasons = failureReasons;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<FiveRightsCheck, Boolean> getOutcomes() {
        return outcomes;
    }

    public Map<FiveRightsCheck, String> getFailureReasons() {
        return failureReasons;
    }

    /** All five rights matched. */
    public boolean allPassed() {
        return outcomes.values().stream().allMatch(Boolean.TRUE::equals);
    }

    /** Subset of rights that failed (empty when {@link #allPassed()} is true). */
    public Set<FiveRightsCheck> failedChecks() {
        EnumSet<FiveRightsCheck> failed = EnumSet.noneOf(FiveRightsCheck.class);
        outcomes.forEach((check, ok) -> {
            if (Boolean.FALSE.equals(ok)) failed.add(check);
        });
        return failed;
    }

    public static final class Builder {
        private final Map<FiveRightsCheck, Boolean> outcomes = new EnumMap<>(FiveRightsCheck.class);
        private final Map<FiveRightsCheck, String> failureReasons = new EnumMap<>(FiveRightsCheck.class);

        public Builder pass(FiveRightsCheck check) {
            outcomes.put(check, Boolean.TRUE);
            failureReasons.remove(check);
            return this;
        }

        public Builder fail(FiveRightsCheck check, String reason) {
            outcomes.put(check, Boolean.FALSE);
            if (reason != null && !reason.isBlank()) {
                failureReasons.put(check, reason);
            }
            return this;
        }

        public FiveRightsVerificationResult build() {
            return new FiveRightsVerificationResult(
                Map.copyOf(outcomes),
                Map.copyOf(failureReasons)
            );
        }
    }
}
