import { serverDateOrInstant } from './date-utils';

/**
 * QA D1077: which measurements a capture form offers for this patient.
 *
 * - Fundal height is an antenatal measurement: never offered for a patient
 *   recorded as male (the server refuses it too). A patient whose sex is not
 *   known yet keeps the field: the triage nurse may be the first to see her.
 * - Head circumference is a growth measurement: offered on the same rule the
 *   chart's growth tab uses (WHO/CDC growth curves stop at 19, so the growth
 *   surface is for a patient under 20). A patient whose date of birth is not
 *   known keeps the field.
 */
const GROWTH_AGE_LIMIT_YEARS = 20;
const YEAR_MS = 365.25 * 24 * 3600 * 1000;

export function isRecordedMale(gender: string | null | undefined): boolean {
  const g = (gender ?? '').trim().toUpperCase();
  return g === 'MALE' || g === 'M';
}

export function offersFundalHeight(gender: string | null | undefined): boolean {
  return !isRecordedMale(gender);
}

/** True when the date of birth puts the patient on the growth surface (under 20). */
export function isGrowthAge(
  dateOfBirth: string | null | undefined,
  now: number = Date.now(),
): boolean {
  if (!dateOfBirth) return false;
  const ageMs = now - serverDateOrInstant(dateOfBirth).getTime();
  return ageMs >= 0 && ageMs < GROWTH_AGE_LIMIT_YEARS * YEAR_MS;
}

/** Head circumference is offered unless the patient is known to be past the growth age. */
export function offersHeadCircumference(
  dateOfBirth: string | null | undefined,
  now: number = Date.now(),
): boolean {
  return !dateOfBirth || isGrowthAge(dateOfBirth, now);
}
