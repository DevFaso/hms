import { serverDateOrInstant } from '../../shared/date-utils';

/** D4154: a stock lot that expires within this many days is flagged "expires soon". */
export const EXPIRING_SOON_DAYS = 30;

/**
 * True when the lot has not expired yet but its expiration date is at most
 * EXPIRING_SOON_DAYS away. A missing or unreadable date is never "soon".
 */
export function isExpiringSoon(
  expirationDate: string | null | undefined,
  expired: boolean,
  now: Date = new Date(),
): boolean {
  if (expired || !expirationDate) return false;
  const on = serverDateOrInstant(expirationDate);
  if (Number.isNaN(on.getTime())) return false;
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const days = Math.round((on.getTime() - today.getTime()) / 86_400_000);
  return days >= 0 && days <= EXPIRING_SOON_DAYS;
}
