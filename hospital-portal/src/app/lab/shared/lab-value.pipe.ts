import { Pipe, PipeTransform } from '@angular/core';
import { currentLocale } from '../../shared/i18n/app-locale';

/** A number as typed: an optional comparator, a sign, digits, an optional decimal part. */
const NUMERIC = /^([<>≤≥]=?\s*)?(-?\d+)(?:[.,](\d+))?$/;

/**
 * QA D1002: a lab value in the reader's number format, keeping exactly the
 * precision the laboratory entered ("0.32" reads "0,32" in French, "0.320"
 * stays three decimals: a lab value is never rounded for display). A value
 * that is not a number (Positif, 1/64, Négatif) is shown as typed.
 */
export function formatLabNumber(
  value: string | number | null | undefined,
  locale = currentLocale(),
): string {
  if (value === null || value === undefined) return '';
  const raw = String(value).trim();
  const m = NUMERIC.exec(raw);
  if (!m) return raw;
  const [, comparator = '', whole, fraction = ''] = m;
  const formatted = new Intl.NumberFormat(locale, {
    minimumFractionDigits: fraction.length,
    maximumFractionDigits: fraction.length,
  }).format(Number(`${whole}.${fraction || '0'}`));
  return `${comparator}${formatted}`;
}

/**
 * `{{ r.resultValue | labValue: r.resultUnit }}` gives "0,32 g/L" in French
 * and "0.32 g/L" in English: the value in the reader's format, a space, then
 * the unit; a bare "—" when there is no value, never a dash with a unit.
 */
@Pipe({ name: 'labValue', standalone: true, pure: false })
export class LabValuePipe implements PipeTransform {
  transform(value: string | number | null | undefined, unit?: string | null): string {
    const formatted = formatLabNumber(value);
    if (!formatted) return '—';
    const u = (unit ?? '').trim();
    return u ? `${formatted} ${u}` : formatted;
  }
}
