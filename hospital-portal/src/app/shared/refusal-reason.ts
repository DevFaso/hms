import { serverMessage } from '../services/provider.model';

/**
 * QA D732/D746: the reason a 4xx refusal carries (the backend localises it,
 * and a validation refusal's field reason wins over the generic text), or
 * `null` for anything else, so a 5xx body never reaches the user and the
 * caller falls back to its own translated message.
 */
export function refusalReason(err: unknown): string | null {
  const status = (err as { status?: unknown } | null)?.status;
  return typeof status === 'number' && status >= 400 && status < 500 ? serverMessage(err) : null;
}
