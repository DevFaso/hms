import { TranslateService } from '@ngx-translate/core';

/**
 * QA D808: a partograph dilation of 15 or an APGAR of 15 was sent to the
 * server, which refused it with the bare "must be less than or equal to 10"
 * and no field named. A form lists the bounds the server's request DTO puts on
 * each number (`@Min`/`@Max`), checks them before sending, and names the field
 * in its message.
 */
export interface FieldLimit {
  /** The request property, as the server names it under `fieldErrors`. */
  field: string;
  /** i18n key of the field's label on the form. */
  labelKey: string;
  min: number;
  /** No upper bound when absent (the server only sets a minimum). */
  max?: number;
}

/** The first filled field outside its bounds, or null when every filled field is within them. */
export function firstOutOfRange(values: object, limits: readonly FieldLimit[]): FieldLimit | null {
  const record = values as Record<string, unknown>;
  for (const limit of limits) {
    const raw = record[limit.field];
    if (raw === null || raw === undefined || raw === '') continue;
    const value = Number(raw);
    if (
      Number.isNaN(value) ||
      value < limit.min ||
      (limit.max !== undefined && value > limit.max)
    ) {
      return limit;
    }
  }
  return null;
}

/** "Dilatation (cm) : la valeur doit être comprise entre 0 et 10." in the active language. */
export function rangeMessage(limit: FieldLimit, translate: TranslateService): string {
  const field = translate.instant(limit.labelKey);
  return limit.max === undefined
    ? translate.instant('FIELD_LIMITS.MIN', { field, min: limit.min })
    : translate.instant('FIELD_LIMITS.RANGE', { field, min: limit.min, max: limit.max });
}

/** The field → label-key map a form passes to `labelledRefusalReason`. */
export function labelKeysOf(limits: readonly FieldLimit[]): Record<string, string> {
  const keys: Record<string, string> = {};
  for (const limit of limits) {
    keys[limit.field] = limit.labelKey;
  }
  return keys;
}
