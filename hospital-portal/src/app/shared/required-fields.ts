import { TranslateService } from '@ngx-translate/core';

/** One required field of a form: the label key it is shown under, and whether it holds a value. */
export interface RequiredField {
  labelKey: string;
  value: unknown;
}

/** True when a form value counts as given: not null, not undefined, not a blank string. */
export function hasValue(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === 'string') return value.trim() !== '';
  return true;
}

/**
 * QA D890: the treatment-plan, consultation and referral forms posted an empty
 * form and answered with a generic or technical toast ("targetSpecialty").
 * Before posting, a form checks its required fields here. Null when every
 * field is filled; otherwise the translated "Required fields missing: …" text
 * naming each missing field by its label, in form order.
 */
export function missingRequiredMessage(
  translate: TranslateService,
  fields: readonly RequiredField[],
): string | null {
  const missing = fields
    .filter((f) => !hasValue(f.value))
    .map((f) => translate.instant(f.labelKey));
  if (missing.length === 0) return null;
  return translate.instant('COMMON.MISSING_REQUIRED_FIELDS', { fields: missing.join(', ') });
}

/**
 * QA D1256 class (round 27): every reason a submit guard has, said at once —
 * a guard reason method that returned the first rule only made the reader
 * press once per rule (empty triage: "Score ESI", then "a vital sign").
 * Null when there is none.
 */
export function joinReasons(
  reasons: readonly (string | null | false | undefined)[],
): string | null {
  const said = reasons
    .filter((r): r is string => typeof r === 'string' && r.trim() !== '')
    .map((r) => r.trim());
  if (said.length <= 1) return said[0] ?? null;
  // Each reason reads as its own sentence, whether its text ends with a stop or not.
  return said.map((r) => (/[.!?…]$/u.test(r) ? r : `${r}.`)).join(' ');
}
