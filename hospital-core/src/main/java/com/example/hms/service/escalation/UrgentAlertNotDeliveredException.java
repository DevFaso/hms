package com.example.hms.service.escalation;

/**
 * Scorecard K4: a notice of an urgent alert reached nobody although somebody was there to reach (every notification
 * and every in-basket item failed). Thrown inside the transaction that claimed the notice, so the claim is undone
 * with it: the first notice stays owed, or the step of the ladder is given back, and the next sweep takes it again.
 * It never reaches a client and carries no text.
 */
public class UrgentAlertNotDeliveredException extends IllegalStateException {

    private static final long serialVersionUID = 1L;
}
