/**
 * An order held for review gives no new dose: the server refuses GIVEN on the request field `givenBeforeHoldAt`. A dose
 * given before the hold must still be chartable, or a double dose becomes possible, so every MAR surface reads the
 * refusal here and asks for the time it was given and why it is charted now.
 */
export const GIVEN_BEFORE_HOLD_FIELD = 'givenBeforeHoldAt';

/** The server's reason when `err` is this refusal, else `null`. */
export function givenBeforeHoldRefusal(err: unknown): string | null {
  const status = (err as { status?: unknown } | null)?.status;
  if (status !== 400) return null;
  const body = (err as { error?: unknown }).error as
    | { field?: unknown; fieldErrors?: Record<string, unknown> | null; message?: unknown }
    | null
    | undefined;
  if (!body || typeof body !== 'object') return null;
  const onField = body.fieldErrors?.[GIVEN_BEFORE_HOLD_FIELD];
  if (typeof onField === 'string' && onField.trim()) return onField.trim();
  if (
    body.field === GIVEN_BEFORE_HOLD_FIELD &&
    typeof body.message === 'string' &&
    body.message.trim()
  ) {
    return body.message.trim();
  }
  return null;
}
