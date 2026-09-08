package com.example.hms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Period;

/**
 * The decay rule for a treatment relationship (E8 #48), as configuration.
 *
 * <p>The decision record is explicit that a relationship which never expires
 * is not a relationship. These three values are that rule written down, and
 * they are the only place it lives — the resolver reads them, nothing else
 * hard-codes a window. Defaults follow Epic's own shape: bounded by the
 * schedule and the admission, with a tail after the visit for follow-up.
 *
 * <pre>
 * app.record-access.post-visit-tail        = P30D   (after checkout / discharge)
 * app.record-access.appointment-lookahead  = P7D    (pre-visit preparation)
 * app.record-access.appointment-lookback   = P1D    (an appointment yesterday
 *                                                    still counts this morning)
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.record-access")
public class RecordAccessProperties {

    /**
     * E8 #49 — the master switch for cross-hospital reads.
     *
     * <p><b>Default false, and it must stay false until the sensitive
     * categories are actually classified.</b> The withhold rule (#51) can only
     * withhold what someone has tagged: with no department default set and no
     * row tagged, turning this on moves every record — psychiatric,
     * substance-use, HIV — across hospitals with nothing held back. Set a
     * default category on the departments that need one, then enable this
     * per environment.
     */
    private boolean crossHospitalReadsEnabled = false;

    /**
     * How long after an encounter is checked out, or an admission discharged,
     * the relationship stays live. Covers results review, discharge follow-up
     * and the post-visit call. ISO-8601 duration; days are the intended unit.
     */
    private Duration postVisitTail = Duration.ofDays(30);

    /**
     * How far ahead a scheduled appointment establishes the relationship, so a
     * clinician can prepare — review outside results, reconcile medications —
     * before the patient is in the room.
     */
    private Period appointmentLookahead = Period.ofDays(7);

    /**
     * How far back an appointment still counts. An appointment dated yesterday
     * may have run past midnight; a day of slack keeps that visit's clinician
     * from being cut off mid-workup.
     */
    private Period appointmentLookback = Period.ofDays(1);
}
