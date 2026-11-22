/**
 * QA D3152 (r70a): the follow-up types the server writes on a discharge
 * summary, never shown as their code. The discharge dialog writes
 * DISCHARGE_FOLLOW_UP and the visit checkout FOLLOW_UP (both « Suivi »); a
 * type a clinician typed in the summary form is free text, shown as written.
 *
 * `FollowUpTypeGuardTest` (hospital-core) fails when the server writes a type
 * that is not listed here.
 */
export const SYSTEM_FOLLOW_UP_TYPE_KEYS: Readonly<Record<string, string>> = {
  DISCHARGE_FOLLOW_UP: 'DISCHARGE.FOLLOW_UP_KIND',
  FOLLOW_UP: 'DISCHARGE.FOLLOW_UP_KIND',
};

/** The translation key of a server-written follow-up type, or null for free text. */
export function systemFollowUpTypeKey(type: string | null | undefined): string | null {
  return type ? (SYSTEM_FOLLOW_UP_TYPE_KEYS[type.trim()] ?? null) : null;
}
