/**
 * QA D1210 (round 23, portal half): the server now refuses a GIVEN dose
 * recorded before the minimum gap since the last GIVEN dose of the same order
 * (q4h: 3 h) unless the nurse gives a reason. The refusal is a 400 on the
 * request field `earlyDoseReason` (`nurse.mar.doseTooSoon`), whose message,
 * already in the reader's language, names the last dose, the earliest next
 * time and the interval.
 *
 * Every portal path that records an administration (the bedside eMAR and the
 * nurse-station MAR table) reads the refusal through this one function and
 * then asks for the reason (`<app-reason-dialog>`), so the nurse can see why
 * the dose was refused and give it early, with the reason kept on the MAR row.
 */

/** The request field the server reads the reason from, and names in its refusal. */
export const EARLY_DOSE_FIELD = 'earlyDoseReason';

/**
 * The server's reason when `err` is the "dose too soon" refusal, else `null`
 * (any other refusal keeps its own handling).
 */
export function earlyDoseRefusal(err: unknown): string | null {
  const status = (err as { status?: unknown } | null)?.status;
  if (status !== 400) return null;
  const body = (err as { error?: unknown }).error as
    | { field?: unknown; fieldErrors?: Record<string, unknown> | null; message?: unknown }
    | null
    | undefined;
  if (!body || typeof body !== 'object') return null;
  const onField = body.fieldErrors?.[EARLY_DOSE_FIELD];
  if (typeof onField === 'string' && onField.trim()) return onField.trim();
  if (body.field === EARLY_DOSE_FIELD && typeof body.message === 'string' && body.message.trim()) {
    return body.message.trim();
  }
  return null;
}
