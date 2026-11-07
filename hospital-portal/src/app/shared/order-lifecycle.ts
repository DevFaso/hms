/**
 * QA D1098 (round 19): which orders a "Cancel" button may be offered on.
 *
 * A resulted imaging order (RESULTS_AVAILABLE) still offered "Annuler la
 * demande": each list kept its own list of closed statuses, and the imaging
 * one forgot the resulted state. The server refuses to cancel an order that
 * has its result or is already closed (CancelledOrders.requireResultStaysClosed,
 * the order services' terminal checks); every order list asks this one rule,
 * and `scripts/lib/author-only-actions.test.mjs` fails on a cancel button of an
 * order list whose gate does not.
 *
 * Statuses across the order types: imaging (COMPLETED, RESULTS_AVAILABLE),
 * ultrasound (COMPLETED, REPORT_AVAILABLE), lab (RESULTED, VERIFIED, COMPLETED,
 * ENTERED_IN_ERROR), procedures, transfusions, consultations (DECLINED),
 * referrals (REJECTED, EXPIRED), prescriptions (DISCONTINUED).
 */
const CLOSED_ORDER_STATUSES: ReadonlySet<string> = new Set([
  'CANCELLED',
  'COMPLETED',
  'RESULTS_AVAILABLE',
  'REPORT_AVAILABLE',
  'RESULTED',
  'VERIFIED',
  'FINAL',
  'ENTERED_IN_ERROR',
  'DECLINED',
  'REJECTED',
  'EXPIRED',
  'DISCONTINUED',
]);

/** True when an order in `status` may still be cancelled (it has no result and is not closed). */
export function isOrderCancellable(status: string | null | undefined): boolean {
  return !!status && !CLOSED_ORDER_STATUSES.has(status);
}

/** The refusal shown under an empty cancellation reason (every cancel states why). */
export const CANCEL_REASON_REQUIRED_KEY = 'ORDER_LIFECYCLE.CANCEL_REASON_REQUIRED';
