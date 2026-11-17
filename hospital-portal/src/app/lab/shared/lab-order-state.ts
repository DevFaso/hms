/**
 * The ONE vocabulary the lab requests page shows for an order: its list, its
 * tabs, its stat cards and its drawer all read an order's state here.
 *
 * The page used to show the raw order status, so a finished order read
 * "Terminé" (COMPLETED) on one row and "Résultats rendus" (RESULTED) on the
 * next, while the tabs said "En attente / Terminé" and counted RESULTED as
 * finished. RESULTED is not finished: the result is entered but nobody has
 * validated (released) it, and the clinician cannot see the value yet. The
 * backend moves an order to COMPLETED only once its last result is released
 * (LabOrderLifecycle.statusAfterAllResultsReleased), and a VERIFIED order is
 * one whose release has not completed it, so both read "awaiting validation".
 */
export type LabOrderState =
  | 'TO_COLLECT'
  | 'COLLECTED'
  | 'IN_ANALYSIS'
  | 'AWAITING_VALIDATION'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'ENTERED_IN_ERROR';

const STATE_OF: Readonly<Record<string, LabOrderState>> = {
  ORDERED: 'TO_COLLECT',
  PENDING: 'TO_COLLECT',
  COLLECTED: 'COLLECTED',
  RECEIVED: 'IN_ANALYSIS',
  IN_PROGRESS: 'IN_ANALYSIS',
  RESULTED: 'AWAITING_VALIDATION',
  VERIFIED: 'AWAITING_VALIDATION',
  COMPLETED: 'COMPLETED',
  CANCELLED: 'CANCELLED',
  ENTERED_IN_ERROR: 'ENTERED_IN_ERROR',
};

/** Every order status the backend has (LabOrderStatus), for the coverage spec. */
export const LAB_ORDER_STATUSES: readonly string[] = Object.keys(STATE_OF);

/** States the laboratory still owes something on: the "in progress" tab and card. */
const OPEN_STATES: ReadonlySet<LabOrderState> = new Set<LabOrderState>([
  'TO_COLLECT',
  'COLLECTED',
  'IN_ANALYSIS',
  'AWAITING_VALIDATION',
]);

/** The state an order status reads as, or null for a status this page does not know. */
export function labOrderState(status: string | null | undefined): LabOrderState | null {
  return STATE_OF[String(status ?? '').toUpperCase()] ?? null;
}

/** The i18n key of each state's label, spelled out so the i18n gates see every one. */
const STATE_KEY: Readonly<Record<LabOrderState, string>> = {
  TO_COLLECT: 'LAB.STATE.TO_COLLECT',
  COLLECTED: 'LAB.STATE.COLLECTED',
  IN_ANALYSIS: 'LAB.STATE.IN_ANALYSIS',
  AWAITING_VALIDATION: 'LAB.STATE.AWAITING_VALIDATION',
  COMPLETED: 'LAB.STATE.COMPLETED',
  CANCELLED: 'LAB.STATE.CANCELLED',
  ENTERED_IN_ERROR: 'LAB.STATE.ENTERED_IN_ERROR',
};

/** The i18n key of the state's label (LAB.STATE.*). */
export function labOrderStateKey(state: LabOrderState): string {
  return STATE_KEY[state];
}

/** Still owed by the laboratory: collection, analysis or validation. */
export function isLabOrderOpen(status: string | null | undefined): boolean {
  const state = labOrderState(status);
  return state != null && OPEN_STATES.has(state);
}

/** Finished: every result released, so the clinician can read them. */
export function isLabOrderFinished(status: string | null | undefined): boolean {
  return labOrderState(status) === 'COMPLETED';
}

/** The badge class of a state, one per state. */
export function labOrderStateClass(state: LabOrderState | null): string {
  switch (state) {
    case 'TO_COLLECT':
      return 'status-badge status-pending';
    case 'COLLECTED':
      return 'status-badge status-collected';
    case 'IN_ANALYSIS':
      return 'status-badge status-in_progress';
    case 'AWAITING_VALIDATION':
      return 'status-badge status-awaiting';
    case 'COMPLETED':
      return 'status-badge status-completed';
    case 'CANCELLED':
    case 'ENTERED_IN_ERROR':
      return 'status-badge status-cancelled';
    default:
      return 'status-badge';
  }
}
