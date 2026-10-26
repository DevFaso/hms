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
