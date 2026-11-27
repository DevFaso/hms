/**
 * QA D3220: « Demander du sang » on a clinical card (labour and delivery, post-partum, the prescription
 * dialog) opens the blood bank's request form for the patient the card is about. The patient travels in
 * the router's navigation state, never in the URL: a patient id in a query string lands in proxy and
 * access logs, in browser history and in front-end telemetry (the rule of patient-search-handoff.ts).
 */
const KEY = 'bloodRequestPatientId';

/** The navigation state that carries the patient to the blood bank's request form. */
export function bloodRequestState(patientId: string): Record<string, string> {
  return { [KEY]: patientId };
}

/**
 * The patient a navigation asked a blood request for, or '' when none. Read once: the entry is cleared
 * from the history state, so reloading the blood bank or coming back to it does not reopen the form.
 */
export function takeBloodRequestPatient(history: History | undefined = globalThis.history): string {
  const state = (history?.state ?? null) as Record<string, unknown> | null;
  const value = state?.[KEY];
  if (typeof value !== 'string') return '';
  try {
    history?.replaceState({ ...state, [KEY]: null }, '');
  } catch {
    // A history that refuses the write still gives the patient once.
  }
  return value.trim();
}
