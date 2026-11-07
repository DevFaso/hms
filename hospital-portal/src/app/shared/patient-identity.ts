import { localDateString } from './date-utils';
import { FieldRefusal } from './forms/invalid-fields';

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

/** The identity fields of a form, each with the id of its element (the middle name is optional). */
export interface IdentityFields {
  dateOfBirth?: { id: string; value?: string | null };
  names: { id: string; value?: string | null }[];
}

/**
 * QA D1422: what is wrong with the typed identity, one refusal per field, each with its
 * own message, for refuseFields. A form that only sets a message leaves the field
 * unmarked and focus on the button: `scripts/lib/identity-refusal.test.mjs` keeps the
 * two rules above behind this.
 */
export function identityRefusals(
  fields: IdentityFields,
  translate: (key: string) => string,
): FieldRefusal[] {
  const out: FieldRefusal[] = [];
  const dob = fields.dateOfBirth;
  const dobKey = dob?.value ? birthDateRefusalKey(dob.value) : null;
  if (dob && dobKey) {
    out.push({ id: dob.id, message: translate(dobKey) });
  }
  for (const name of fields.names) {
    if (name.value && isMadeOfDigits(name.value)) {
      out.push({ id: name.id, message: translate('PATIENTS.NAME_DIGITS_ONLY') });
    }
  }
  return out;
}
