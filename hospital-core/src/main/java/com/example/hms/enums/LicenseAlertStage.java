package com.example.hms.enums;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How close a practitioner's licence is to expiry (Tier 2 item 40).
 *
 * <p>This enum is the single owner of the thresholds. They already existed
 * as an inline if/else chain inside {@code HospitalAdminDashboardServiceImpl},
 * which was fine while one screen read them and becomes a drift hazard the
 * moment a scheduler reads them too — the recurring defect in this codebase
 * is a rule copied to a second call site and the copies diverging. The
 * dashboard now delegates here.
 *
 * <p>The ordinal order is deliberate and load-bearing: the expiry sweep
 * notifies only when a staff member's recorded stage <em>advances</em>, and
 * "advances" is defined by {@link #isMoreSevereThan}. A daily
 * re-notification for the same licence would be worse than silence — it
 * trains an administrator to dismiss the category, and then the one that
 * mattered goes with it.
 */
public enum LicenseAlertStage {

    /** Expires within the horizon but is not urgent yet. */
    WARNING,

    /** Expires soon enough that renewal has to start now. */
    CRITICAL,

    /** Already expired. The practitioner is working without a current licence. */
    EXPIRED;

    /** Licences expiring within this many days are surfaced at all. */
    public static final int WARNING_DAYS = 90;

    /** Within this many days the licence is CRITICAL rather than WARNING. */
    public static final int CRITICAL_DAYS = 30;

    /**
     * Grade a licence, or {@code null} when there is nothing to say — no
     * expiry date recorded, or an expiry comfortably beyond the horizon.
     *
     * <p>{@code null} rather than a fourth OK constant on purpose: "nothing
     * to report" is the absence of an alert, and giving it a name invites a
     * caller to store it and then compare it as though it were a stage.
     *
     * @param expiryDate may be null — most staff rows have never had one
     * @param today      passed in rather than read from the wall clock so
     *                   this stays testable
     */
    public static LicenseAlertStage grade(LocalDate expiryDate, LocalDate today) {
        if (expiryDate == null || today == null) {
            return null;
        }
        long daysUntil = ChronoUnit.DAYS.between(today, expiryDate);
        if (daysUntil < 0) {
            return EXPIRED;
        }
        if (daysUntil <= CRITICAL_DAYS) {
            return CRITICAL;
        }
        if (daysUntil <= WARNING_DAYS) {
            return WARNING;
        }
        return null;
    }

    /**
     * Whether this stage is worse than one already notified.
     *
     * <p>A null {@code previous} means nothing has been notified yet, so any
     * stage is an advance.
     */
    public boolean isMoreSevereThan(LicenseAlertStage previous) {
        return previous == null || this.ordinal() > previous.ordinal();
    }
}
