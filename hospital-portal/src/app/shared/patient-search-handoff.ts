/**
 * QA D3750: a patient search typed somewhere else (the dashboard's header search) and carried to
 * the patient list. It travels in the router's navigation state, never in the URL: a query string
 * lands in proxy and access logs, in browser history and in front-end telemetry.
 */
const KEY = 'patientSearch';

/** The navigation extras that carry `term` to the patient list. */
export function patientSearchState(term: string): { state: Record<string, string> } {
  return { state: { [KEY]: term } };
}

/**
 * The term a navigation carried to this page, or '' when none. It is read once: the entry is
 * cleared from the history state so that reloading or coming back does not replay it.
 */
export function takePatientSearch(history: History | undefined = globalThis.history): string {
  const state = (history?.state ?? null) as Record<string, unknown> | null;
  const value = state?.[KEY];
  if (typeof value !== 'string') return '';
  try {
    history?.replaceState({ ...state, [KEY]: null }, '');
  } catch {
    // A history that refuses the write still gives the term once.
  }
  return value.trim();
}
