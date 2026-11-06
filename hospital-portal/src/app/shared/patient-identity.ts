import { localDateString } from './date-utils';

/** QA D1414: mirrors PatientIdentityRules.OLDEST_AGE_YEARS on the server. */
export const OLDEST_AGE_YEARS = 130;

/** The latest birth date the server accepts: yesterday (the record's date must be in the past). */
export function latestBirthDate(now: Date = new Date()): string {
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  return localDateString(yesterday);
}

/** The earliest birth date the server accepts. */
export function earliestBirthDate(now: Date = new Date()): string {
  const oldest = new Date(now.getFullYear() - OLDEST_AGE_YEARS, now.getMonth(), now.getDate());
  return localDateString(oldest);
}

/**
 * QA D1403 + D1414: the translation key of what is wrong with a typed birth date
 * (yyyy-mm-dd), or null. Same rule as the server, on the field, before the request.
 */
export function birthDateRefusalKey(value: string, now: Date = new Date()): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return null;
  }
  if (value > latestBirthDate(now)) {
    return 'PATIENTS.DOB_NOT_PAST';
  }
  return value < earliestBirthDate(now) ? 'PATIENTS.DOB_IMPOSSIBLE' : null;
}

/** QA D1414: at least one digit and no letter ("A1" is a name, "12345" is not; "-" is not digits). */
export function isMadeOfDigits(value: string): boolean {
  return /\p{Nd}/u.test(value) && !/\p{L}/u.test(value);
}
